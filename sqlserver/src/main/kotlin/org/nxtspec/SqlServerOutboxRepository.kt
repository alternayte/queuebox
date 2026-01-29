package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.OutboxRepositoryInterface
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
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

    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val now = Clock.System.now()
        val nowTimestamp = Timestamp.from(java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong()))

        // Escape column names that are SQL Server reserved words
        val idCol = escapeSqlServerColumnName(columnMapping.id)
        val topicCol = escapeSqlServerColumnName(columnMapping.topic)
        val keyCol = escapeSqlServerColumnName(columnMapping.key)
        val payloadCol = escapeSqlServerColumnName(columnMapping.payload)
        val headersCol = escapeSqlServerColumnName(columnMapping.headers)
        val stateCol = escapeSqlServerColumnName(columnMapping.state)
        val attemptCol = escapeSqlServerColumnName(columnMapping.attempt)
        val maxAttemptsCol = escapeSqlServerColumnName(columnMapping.maxAttempts)
        val scheduledAtCol = escapeSqlServerColumnName(columnMapping.scheduledAt)
        val createdAtCol = escapeSqlServerColumnName(columnMapping.createdAt)
        val updatedAtCol = escapeSqlServerColumnName(columnMapping.updatedAt)

        // Use raw SQL with SQL Server-specific locking hints
        // ROWLOCK: Lock at row level
        // UPDLOCK: Take update locks to prevent other transactions from modifying
        // READPAST: Skip locked rows (equivalent to SKIP LOCKED in PostgreSQL)
        val sql = """
            SELECT TOP (?) $idCol, $topicCol, $keyCol, $payloadCol, $headersCol, $stateCol, $attemptCol, $maxAttemptsCol,
                   $scheduledAtCol, $createdAtCol, $updatedAtCol
            FROM $tableName WITH (ROWLOCK, UPDLOCK, READPAST)
            WHERE $stateCol = 'pending' AND $scheduledAtCol <= ?
        """.trimIndent()

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val messages = conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, batchSize)
            stmt.setTimestamp(2, nowTimestamp)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<OutboxMessage>()
                while (rs.next()) {
                    results.add(rs.toOutboxMessage())
                }
                results
            }
        }

        if (messages.isNotEmpty()) {
            table.update({ table.id inList messages.map { it.id } }) {
                it[state] = "processing"
                it[updatedAt] = now
            }
        }

        messages
    }

    override suspend fun markSent(id: UUID) = newSuspendedTransaction {
        updateState(id, "sent")
    }

    override suspend fun markFailed(id: UUID, error: String): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[attempt] = table.attempt + 1
            it[state] = "failed"
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        val scheduledTime = now + delayMs.milliseconds
        table.update({ table.id eq id }) {
            it[scheduledAt] = scheduledTime
            it[state] = "pending"
            it[attempt] = table.attempt + 1
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun markDead(id: UUID) = newSuspendedTransaction {
        updateState(id, "dead")
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        table.deleteWhere {
            (table.state eq state) and (table.updatedAt less cutoff)
        }
    }

    override suspend fun deleteExceptMostRecent(state: String, keepCount: Int): Int = newSuspendedTransaction {
        val idsToKeep = table
            .select(table.id)
            .where { table.state eq state }
            .orderBy(table.updatedAt, SortOrder.DESC)
            .limit(keepCount)

        table.deleteWhere {
            (table.state eq state) and (table.id notInSubQuery idsToKeep)
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

    private fun parseHeadersJson(json: String): Map<String, String> {
        return try {
            Json.decodeFromString<Map<String, String>>(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "sent" -> MessageState.Sent
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
