package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
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

    override suspend fun claimBatch(batchSize: Int, leaseMs: Long): List<OutboxMessage> = joinOrNewTransaction {
        require(batchSize > 0 && leaseMs in 1..Int.MAX_VALUE.toLong())
        val t = q(tableName)
        val sql = """
            UPDATE $t AS target
            SET ${q(columnMapping.state)} = 'processing',
                ${q(columnMapping.updatedAt)} = ?,
                ${q(columnMapping.claimedAt)} = ?,
                ${q(columnMapping.claimToken)} = gen_random_uuid(),
                ${q(columnMapping.leaseExpiresAt)} = clock_timestamp() + INTERVAL '1 millisecond' * $leaseMs
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
                      target.${q(
            columnMapping.updatedAt
        )}, target.${q(
            columnMapping.claimedAt
        )}, target.${q(columnMapping.claimToken)}, target.${q(columnMapping.leaseExpiresAt)}
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

    override suspend fun insert(message: OutboxMessage): Unit = joinOrNewTransaction {
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

    override suspend fun markSent(id: UUID, claimToken: UUID?): Boolean = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({ claimFence(id, claimToken) }) {
            it[table.state] = "sent"
            it[table.updatedAt] = now
        } > 0
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long, claimToken: UUID?, error: String?): Boolean =
        joinOrNewTransaction {
            val now = Clock.System.now()
            val scheduledTime = now + delayMs.milliseconds
            table.update({ claimFence(id, claimToken) }) {
                it[table.scheduledAt] = scheduledTime
                it[table.state] = "pending"
                it[table.attempt] = table.attempt + 1
                it[table.updatedAt] = now
                it[table.claimedAt] = null
                if (error != null) it[table.lastError] = error
            } > 0
        }

    override suspend fun markDead(id: UUID, claimToken: UUID?, error: String?): Boolean = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({ claimFence(id, claimToken) }) {
            it[table.state] = "dead"
            it[table.updatedAt] = now
            it[table.claimedAt] = null
            if (error != null) it[table.lastError] = error
        } > 0
    }

    private fun claimFence(id: UUID, claimToken: UUID?): org.jetbrains.exposed.sql.Op<Boolean> {
        val base = (table.id eq id) and (table.state eq "processing")
        return if (claimToken == null) {
            org.jetbrains.exposed.sql.Op.FALSE
        } else {
            base and (table.claimToken eq claimToken) and (table.leaseExpiresAt greater databaseNow)
        }
    }

    private val databaseNow = object : org.jetbrains.exposed.sql.Expression<Instant>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
            queryBuilder.append("clock_timestamp()")
        }
    }

    override suspend fun renewClaim(id: UUID, claimToken: UUID?, leaseMs: Long): Boolean = joinOrNewTransaction {
        require(leaseMs > 0)
        val expires = object : org.jetbrains.exposed.sql.Expression<Instant>() {
            override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
                queryBuilder.append("clock_timestamp() + INTERVAL '1 millisecond' * $leaseMs")
            }
        }
        table.update({ claimFence(id, claimToken) }) { it[leaseExpiresAt] = expires } > 0
    }

    override suspend fun nextWakeDelayMs(maxWaitMs: Long): Long = joinOrNewTransaction {
        val sql = """
            SELECT EXTRACT(EPOCH FROM (MIN(due) - clock_timestamp())) * 1000 FROM (
                SELECT ${q(columnMapping.scheduledAt)} AS due FROM ${q(tableName)}
                WHERE ${q(columnMapping.state)} = 'pending' AND ${q(columnMapping.scheduledAt)} > clock_timestamp()
                UNION ALL
                SELECT ${q(columnMapping.leaseExpiresAt)} AS due FROM ${q(tableName)}
                WHERE ${q(
            columnMapping.state
        )} = 'processing' AND ${q(columnMapping.leaseExpiresAt)} > clock_timestamp()
            ) AS deadlines
        """.trimIndent()
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .connection.connection as java.sql.Connection
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rows ->
                rows.next()
                val delay = rows.getLong(1)
                if (rows.wasNull()) maxWaitMs else delay.coerceIn(1, maxWaitMs)
            }
        }
    }

    override suspend fun countByState(state: String): Long = joinOrNewTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    override suspend fun reclaimStale(olderThan: Duration): Int = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({
            (table.state eq "processing") and
                ((table.leaseExpiresAt lessEq databaseNow) or table.leaseExpiresAt.isNull())
        }) {
            it[table.state] = "pending"
            it[table.updatedAt] = now
            it[table.claimedAt] = null
        }
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int {
        require(state in setOf("sent", "processed", "dead")) { "Cannot delete active work" }
        return joinOrNewTransaction {
            val ids = table
                .select(table.id)
                .where { (table.state eq state) and (table.updatedAt less cutoff) }
                .limit(limit)
                .map { it[table.id] }

            if (ids.isEmpty()) {
                0
            } else {
                table.deleteWhere { (table.id inList ids) and (table.state eq state) }
            }
        }
    }

    override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int {
        require(state in setOf("sent", "dead")) { "Cannot delete active work" }
        return joinOrNewTransaction {
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
                table.deleteWhere { (table.id inList ids) and (table.state eq state) }
            }
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
            updatedAt = getTimestamp(columnMapping.updatedAt).toKotlinInstant(),
            claimToken = getString(columnMapping.claimToken)?.let(UUID::fromString),
            leaseExpiresAt = getTimestamp(columnMapping.leaseExpiresAt)?.toInstant()?.let {
                Instant.fromEpochSeconds(it.epochSecond, it.nano)
            },
            claimedAt = getTimestamp(columnMapping.claimedAt)?.toKotlinInstant()
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
            updatedAt = this[table.updatedAt],
            claimToken = this[table.claimToken],
            leaseExpiresAt = this[table.leaseExpiresAt],
            claimedAt = this[table.claimedAt]
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
