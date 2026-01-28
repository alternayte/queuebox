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
class SqlServerOutboxRepository : OutboxRepositoryInterface {

    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val now = Clock.System.now()
        val nowTimestamp = Timestamp.from(java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong()))

        // Use raw SQL with SQL Server-specific locking hints
        // ROWLOCK: Lock at row level
        // UPDLOCK: Take update locks to prevent other transactions from modifying
        // READPAST: Skip locked rows (equivalent to SKIP LOCKED in PostgreSQL)
        val sql = """
            SELECT TOP (?) id, topic, [key], payload, state, attempt, max_attempts,
                   scheduled_at, created_at, updated_at
            FROM outbox WITH (ROWLOCK, UPDLOCK, READPAST)
            WHERE state = 'pending' AND scheduled_at <= ?
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
            SqlServerOutboxTable.update({ SqlServerOutboxTable.id inList messages.map { it.id } }) {
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
        SqlServerOutboxTable.update({ SqlServerOutboxTable.id eq id }) {
            it[attempt] = SqlServerOutboxTable.attempt + 1
            it[state] = "failed"
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        val scheduledTime = now + delayMs.milliseconds
        SqlServerOutboxTable.update({ SqlServerOutboxTable.id eq id }) {
            it[scheduledAt] = scheduledTime
            it[state] = "pending"
            it[attempt] = SqlServerOutboxTable.attempt + 1
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun markDead(id: UUID) = newSuspendedTransaction {
        updateState(id, "dead")
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        SqlServerOutboxTable
            .selectAll()
            .where { SqlServerOutboxTable.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        SqlServerOutboxTable.deleteWhere {
            (SqlServerOutboxTable.state eq state) and (SqlServerOutboxTable.updatedAt less cutoff)
        }
    }

    override suspend fun deleteExceptMostRecent(state: String, keepCount: Int): Int = newSuspendedTransaction {
        val idsToKeep = SqlServerOutboxTable
            .select(SqlServerOutboxTable.id)
            .where { SqlServerOutboxTable.state eq state }
            .orderBy(SqlServerOutboxTable.updatedAt, SortOrder.DESC)
            .limit(keepCount)

        SqlServerOutboxTable.deleteWhere {
            (SqlServerOutboxTable.state eq state) and (SqlServerOutboxTable.id notInSubQuery idsToKeep)
        }
    }

    private fun updateState(id: UUID, newState: String) {
        val now = Clock.System.now()
        SqlServerOutboxTable.update({ SqlServerOutboxTable.id eq id }) {
            it[state] = newState
            it[updatedAt] = now
        }
    }

    private fun ResultRow.toOutboxMessage(): OutboxMessage = OutboxMessage(
        id = this[SqlServerOutboxTable.id].value,
        topic = this[SqlServerOutboxTable.topic],
        key = this[SqlServerOutboxTable.key],
        payload = Json.parseToJsonElement(this[SqlServerOutboxTable.payload]),
        state = stringToMessageState(this[SqlServerOutboxTable.state]),
        attempt = this[SqlServerOutboxTable.attempt],
        maxAttempts = this[SqlServerOutboxTable.maxAttempts],
        scheduledAt = this[SqlServerOutboxTable.scheduledAt],
        createdAt = this[SqlServerOutboxTable.createdAt],
        updatedAt = this[SqlServerOutboxTable.updatedAt]
    )

    private fun ResultSet.toOutboxMessage(): OutboxMessage = OutboxMessage(
        id = UUID.fromString(getString("id")),
        topic = getString("topic"),
        key = getString("key"),
        payload = Json.parseToJsonElement(getString("payload")),
        state = stringToMessageState(getString("state")),
        attempt = getInt("attempt"),
        maxAttempts = getInt("max_attempts"),
        scheduledAt = getTimestamp("scheduled_at").toInstant().toKotlinInstant(),
        createdAt = getTimestamp("created_at").toInstant().toKotlinInstant(),
        updatedAt = getTimestamp("updated_at").toInstant().toKotlinInstant()
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "sent" -> MessageState.Sent
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
