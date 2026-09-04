package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.OutboxRepositoryInterface
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * SQL Server implementation of the outbox repository.
 * Uses ROWLOCK, UPDLOCK, READPAST table hints for concurrent batch claiming,
 * which is the SQL Server equivalent of PostgreSQL's FOR UPDATE SKIP LOCKED.
 */
class SqlServerOutboxRepository(
    private val columnMapping: OutboxColumnMapping = OutboxColumnMapping(),
    private val tableName: String = "outbox"
) : OutboxRepositoryInterface {
    private val table = SqlServerDynamicOutboxTable(columnMapping, tableName)

    /**
     * F-009 and F-006: claims the oldest scheduled messages in one statement.
     *
     * The common table expression takes the row locks with ROWLOCK, UPDLOCK and READPAST, which
     * is the SQL Server equivalent of FOR UPDATE SKIP LOCKED. The UPDATE runs through the same
     * expression and OUTPUT returns the claimed rows, so the claim and the mark are atomic.
     * claimed_at lets the reclaim step recover a crashed claim.
     */
    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val now = Clock.System.now()
        val nowTimestamp = Timestamp.from(
            java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
        )

        val t = quoteSqlServerIdentifier(tableName)
        val idCol = quoteSqlServerIdentifier(columnMapping.id)
        val stateCol = quoteSqlServerIdentifier(columnMapping.state)
        val scheduledAtCol = quoteSqlServerIdentifier(columnMapping.scheduledAt)
        val createdAtCol = quoteSqlServerIdentifier(columnMapping.createdAt)
        val updatedAtCol = quoteSqlServerIdentifier(columnMapping.updatedAt)
        val claimedAtCol = quoteSqlServerIdentifier(columnMapping.claimedAt)

        val sql = """
            WITH candidates AS (
                SELECT TOP (?) $idCol, $stateCol, $updatedAtCol, $claimedAtCol
                FROM $t WITH (ROWLOCK, UPDLOCK, READPAST)
                WHERE $stateCol = 'pending' AND $scheduledAtCol <= ?
                ORDER BY $scheduledAtCol ASC, $createdAtCol ASC
            )
            UPDATE candidates
            SET $stateCol = 'processing', $updatedAtCol = ?, $claimedAtCol = ?
            OUTPUT INSERTED.$idCol AS ${quoteSqlServerIdentifier(columnMapping.id)}
        """.trimIndent()

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val claimedIds = conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, batchSize)
            stmt.setTimestamp(2, nowTimestamp)
            stmt.setTimestamp(3, nowTimestamp)
            stmt.setTimestamp(4, nowTimestamp)
            stmt.executeQuery().use { rs ->
                val ids = mutableListOf<UUID>()
                while (rs.next()) {
                    ids.add(UUID.fromString(rs.getString(columnMapping.id)))
                }
                ids
            }
        }

        if (claimedIds.isEmpty()) {
            emptyList()
        } else {
            // OUTPUT cannot return every column through a common table expression update on
            // every SQL Server edition, so the rows are read back by identifier. The read runs
            // in the same transaction, and the rows are already marked, so no other replica
            // can take them.
            val byId = table
                .selectAll()
                .where { table.id inList claimedIds }
                .associate { it[table.id].value to it.toOutboxMessage() }
            // OUTPUT does not guarantee an output order, so the claim order is restored here.
            claimedIds.mapNotNull { byId[it] }
                .sortedWith(compareBy({ it.scheduledAt }, { it.createdAt }))
        }
    }

    override suspend fun insert(message: OutboxMessage): Unit = newSuspendedTransaction {
        table.insert {
            it[id] = message.id
            it[topic] = message.topic
            it[key] = message.key
            it[payload] = message.payload.toString()
            it[headers] = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), message.headers)
            it[state] = "pending"
            it[attempt] = message.attempt
            it[maxAttempts] = message.maxAttempts
            it[scheduledAt] = message.scheduledAt
            it[createdAt] = message.createdAt
            it[updatedAt] = message.updatedAt
        }
        Unit
    }

    override suspend fun markSent(id: UUID) = newSuspendedTransaction {
        updateState(id, "sent")
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long, error: String?): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        val scheduledTime = now + delayMs.milliseconds
        table.update({ table.id eq id }) {
            it[scheduledAt] = scheduledTime
            it[state] = "pending"
            it[attempt] = table.attempt + 1
            it[updatedAt] = now
            it[claimedAt] = null
            if (error != null) it[lastError] = error
        }
        Unit
    }

    override suspend fun markDead(id: UUID, error: String?): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[state] = "dead"
            it[updatedAt] = now
            it[claimedAt] = null
            if (error != null) it[lastError] = error
        }
        Unit
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    /**
     * F-006: returns rows that stay in state 'processing' longer than the visibility timeout
     * back to state 'pending'. The attempt count does not change.
     */
    override suspend fun reclaimStale(olderThan: Duration): Int = newSuspendedTransaction {
        val cutoff = Clock.System.now() - olderThan
        val now = Clock.System.now()
        table.update({
            (table.state eq "processing") and
                ((table.claimedAt lessEq cutoff) or table.claimedAt.isNull())
        }) {
            it[state] = "pending"
            it[updatedAt] = now
            it[claimedAt] = null
        }
    }

    /**
     * F-008: deletes at most `limit` rows per statement.
     */
    override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = newSuspendedTransaction {
        val ids = table
            .select(table.id)
            .where { (table.state eq state) and (table.updatedAt less cutoff) }
            .limit(limit)
            .map { it[table.id] }

        if (ids.isEmpty()) {
            0
        } else {
            table.deleteWhere { table.id inList ids }
        }
    }

    override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int =
        newSuspendedTransaction {
            val idsToKeep = table
                .select(table.id)
                .where { table.state eq state }
                .orderBy(table.updatedAt, SortOrder.DESC)
                .limit(keepCount)
                .map { it[table.id] }

            val ids = table
                .select(table.id)
                .where { (table.state eq state) and (table.id notInList idsToKeep) }
                .limit(limit)
                .map { it[table.id] }

            if (ids.isEmpty()) {
                0
            } else {
                table.deleteWhere { table.id inList ids }
            }
        }

    private fun updateState(id: UUID, newState: String) {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[state] = newState
            it[updatedAt] = now
        }
    }

    private fun ResultRow.toOutboxMessage(): OutboxMessage {
        val headersJson = this[table.headers]
        val headers = parseHeadersJson(headersJson)
        return OutboxMessage(
            id = this[table.id].value,
            topic = this[table.topic],
            key = this[table.key],
            payload = Json.parseToJsonElement(this[table.payload]),
            headers = headers,
            state = stringToMessageState(this[table.state]),
            attempt = this[table.attempt],
            maxAttempts = this[table.maxAttempts],
            scheduledAt = this[table.scheduledAt],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }

    private fun ResultSet.toOutboxMessage(): OutboxMessage {
        val headersJson = getString(columnMapping.headers) ?: "{}"
        val headers = parseHeadersJson(headersJson)
        return OutboxMessage(
            id = UUID.fromString(getString(columnMapping.id)),
            topic = getString(columnMapping.topic),
            key = getString(columnMapping.key),
            payload = Json.parseToJsonElement(getString(columnMapping.payload)),
            headers = headers,
            state = stringToMessageState(getString(columnMapping.state)),
            attempt = getInt(columnMapping.attempt),
            maxAttempts = getInt(columnMapping.maxAttempts),
            scheduledAt = getTimestamp(columnMapping.scheduledAt).toInstant().toKotlinInstant(),
            createdAt = getTimestamp(columnMapping.createdAt).toInstant().toKotlinInstant(),
            updatedAt = getTimestamp(columnMapping.updatedAt).toInstant().toKotlinInstant()
        )
    }

    private fun parseHeadersJson(json: String): Map<String, String> = try {
        Json.decodeFromString<Map<String, String>>(json)
    } catch (e: Exception) {
        emptyMap()
    }

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "sent" -> MessageState.Sent
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
