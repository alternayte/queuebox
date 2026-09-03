package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class OutboxRepository(
    private val columnMapping: OutboxColumnMapping = OutboxColumnMapping(),
    private val tableName: String = "outbox"
) : OutboxRepositoryInterface {
    private val table = DynamicOutboxTable(columnMapping, tableName)

    // Every identifier that a raw SQL string interpolates passes through this function.
    // ConfigValidator rejects an identifier that is not a plain SQL identifier. Quoting is
    // the second defence. See F-011.
    private fun q(identifier: String): String {
        require(!identifier.contains('"')) { "Invalid SQL identifier: '$identifier'" }
        return "\"$identifier\""
    }
    /**
     * F-009 and F-006: claims the oldest scheduled messages in one statement.
     *
     * The inner SELECT takes the row locks with FOR UPDATE SKIP LOCKED, so a second replica
     * takes different work instead of waiting. The UPDATE marks the rows and returns them, so
     * the claim and the mark are atomic. claimed_at lets the reclaim step recover a crashed
     * claim.
     */
    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val t = q(tableName)
        val sql = """
            UPDATE $t AS target
            SET ${q(columnMapping.state)} = 'processing',
                ${q(columnMapping.updatedAt)} = ?,
                ${q(columnMapping.claimedAt)} = ?
            FROM (
                SELECT ${q(columnMapping.id)} AS claim_id
                FROM $t
                WHERE ${q(columnMapping.state)} = 'pending'
                  AND ${q(columnMapping.scheduledAt)} <= ?
                ORDER BY ${q(columnMapping.scheduledAt)} ASC, ${q(columnMapping.createdAt)} ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            ) AS candidates
            WHERE target.${q(columnMapping.id)} = candidates.claim_id
            RETURNING target.${q(columnMapping.id)}, target.${q(columnMapping.topic)},
                      target.${q(columnMapping.key)}, target.${q(columnMapping.payload)},
                      target.${q(columnMapping.headers)}, target.${q(columnMapping.state)},
                      target.${q(columnMapping.attempt)}, target.${q(columnMapping.maxAttempts)},
                      target.${q(columnMapping.scheduledAt)}, target.${q(columnMapping.createdAt)},
                      target.${q(columnMapping.updatedAt)}
        """.trimIndent()

        val now = Clock.System.now()
        val nowTimestamp = java.sql.Timestamp.from(
            java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
        )

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, nowTimestamp)
            stmt.setTimestamp(2, nowTimestamp)
            stmt.setTimestamp(3, nowTimestamp)
            stmt.setInt(4, batchSize)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<OutboxMessage>()
                while (rs.next()) {
                    results.add(rs.toOutboxMessage())
                }
                // RETURNING does not guarantee an output order, so the caller order is
                // restored here. The poller then processes the oldest message first.
                results.sortedWith(compareBy({ it.scheduledAt }, { it.createdAt }))
            }
        }
    }

    override suspend fun insert(message: OutboxMessage): Unit = newSuspendedTransaction {
        table.insert {
            it[table.id] = message.id
            it[table.topic] = message.topic
            it[table.key] = message.key
            it[table.payload] = message.payload
            it[table.headers] = JsonObject(message.headers.mapValues { (_, v) -> JsonPrimitive(v) })
            it[table.state] = "pending"
            it[table.attempt] = message.attempt
            it[table.maxAttempts] = message.maxAttempts
            it[table.scheduledAt] = message.scheduledAt
            it[table.createdAt] = message.createdAt
            it[table.updatedAt] = message.updatedAt
        }
        Unit
    }

    override suspend fun markSent(id: UUID) = newSuspendedTransaction {
        updateState(id, "sent")
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long, error: String?): Unit =
        newSuspendedTransaction {
            val now = Clock.System.now()
            val scheduledTime = now + delayMs.milliseconds
            table.update({ table.id eq id }) {
                it[table.scheduledAt] = scheduledTime
                it[table.state] = "pending"
                it[table.attempt] = table.attempt + 1
                it[table.updatedAt] = now
                it[table.claimedAt] = null
                if (error != null) it[table.lastError] = error
            }
            Unit
        }

    override suspend fun markDead(id: UUID, error: String?): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[table.state] = "dead"
            it[table.updatedAt] = now
            it[table.claimedAt] = null
            if (error != null) it[table.lastError] = error
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
     * back to state 'pending'. The attempt count does not change, because the message was
     * never delivered.
     */
    override suspend fun reclaimStale(olderThan: Duration): Int = newSuspendedTransaction {
        val cutoff = Clock.System.now() - olderThan
        val now = Clock.System.now()
        table.update({
            (table.state eq "processing") and
                ((table.claimedAt lessEq cutoff) or table.claimedAt.isNull())
        }) {
            it[table.state] = "pending"
            it[table.updatedAt] = now
            it[table.claimedAt] = null
        }
    }

    /**
     * F-008: deletes at most `limit` rows per statement, so a cleanup never holds locks on the
     * whole table.
     */
    override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int =
        newSuspendedTransaction {
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
                .orderBy(table.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
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
            it[table.state] = newState
            it[table.updatedAt] = now
        }
    }

    private fun java.sql.ResultSet.toOutboxMessage(): OutboxMessage {
        val headersJson = getString(columnMapping.headers) ?: "{}"
        val headers = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(headersJson) as JsonObject)
                .mapValues { it.value.jsonPrimitive.content }
        }.getOrElse { emptyMap() }

        return OutboxMessage(
            id = UUID.fromString(getString(columnMapping.id)),
            topic = getString(columnMapping.topic),
            key = getString(columnMapping.key),
            payload = kotlinx.serialization.json.Json.parseToJsonElement(getString(columnMapping.payload)),
            headers = headers,
            state = stringToMessageState(getString(columnMapping.state)),
            attempt = getInt(columnMapping.attempt),
            maxAttempts = getInt(columnMapping.maxAttempts),
            scheduledAt = getTimestamp(columnMapping.scheduledAt).toKotlinInstant(),
            createdAt = getTimestamp(columnMapping.createdAt).toKotlinInstant(),
            updatedAt = getTimestamp(columnMapping.updatedAt).toKotlinInstant()
        )
    }

    private fun java.sql.Timestamp.toKotlinInstant(): Instant = toInstant().let {
        Instant.fromEpochSeconds(it.epochSecond, it.nano)
    }

    private fun ResultRow.toOutboxMessage(): OutboxMessage {
        val headersJson = this[table.headers]
        val headers = if (headersJson is JsonObject) {
            headersJson.mapValues { it.value.jsonPrimitive.content }
        } else {
            emptyMap()
        }
        return OutboxMessage(
            id = this[table.id].value,
            topic = this[table.topic],
            key = this[table.key],
            payload = this[table.payload],
            headers = headers,
            state = stringToMessageState(this[table.state]),
            attempt = this[table.attempt],
            maxAttempts = this[table.maxAttempts],
            scheduledAt = this[table.scheduledAt],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "sent" -> MessageState.Sent
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
