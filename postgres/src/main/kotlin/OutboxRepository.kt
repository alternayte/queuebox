package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class OutboxRepository(
    columnMapping: OutboxColumnMapping = OutboxColumnMapping(),
    tableName: String = "outbox"
) : OutboxRepositoryInterface {
    private val table = DynamicOutboxTable(columnMapping, tableName)
    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val now = Clock.System.now()
        val messages = table
            .selectAll()
            .where { (table.state eq "pending") and (table.scheduledAt lessEq now) }
            .limit(batchSize)
            .forUpdate()
            .map { it.toOutboxMessage() }

        if (messages.isNotEmpty()) {
            table.update({ table.id inList messages.map { it.id } }) {
                it[table.state] = "processing"
                it[table.updatedAt] = now
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
            it[table.attempt] = table.attempt + 1
            it[table.state] = "failed"
            it[table.updatedAt] = now
        }
        Unit
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        val scheduledTime = now + delayMs.milliseconds
        table.update({ table.id eq id }) {
            it[table.scheduledAt] = scheduledTime
            it[table.state] = "pending"
            it[table.attempt] = table.attempt + 1
            it[table.updatedAt] = now
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
            .orderBy(table.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(keepCount)

        table.deleteWhere {
            (table.state eq state) and (table.id notInSubQuery idsToKeep)
        }
    }

    private fun updateState(id: UUID, newState: String) {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[table.state] = newState
            it[table.updatedAt] = now
        }
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
