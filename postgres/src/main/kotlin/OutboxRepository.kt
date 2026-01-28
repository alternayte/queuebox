package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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

class OutboxRepository : OutboxRepositoryInterface {
    override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = newSuspendedTransaction {
        val now = Clock.System.now()
        val messages = OutboxTable
            .selectAll()
            .where { (OutboxTable.state eq "pending") and (OutboxTable.scheduledAt lessEq now) }
            .limit(batchSize)
            .forUpdate()
            .map { it.toOutboxMessage() }

        if (messages.isNotEmpty()) {
            OutboxTable.update({ OutboxTable.id inList messages.map { it.id } }) {
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
        OutboxTable.update({ OutboxTable.id eq id }) {
            it[attempt] = OutboxTable.attempt + 1
            it[state] = "failed"
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun scheduleRetry(id: UUID, delayMs: Long): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        val scheduledTime = now + delayMs.milliseconds
        OutboxTable.update({ OutboxTable.id eq id }) {
            it[scheduledAt] = scheduledTime
            it[state] = "pending"
            it[attempt] = OutboxTable.attempt + 1
            it[updatedAt] = now
        }
        Unit
    }

    override suspend fun markDead(id: UUID) = newSuspendedTransaction {
        updateState(id, "dead")
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        OutboxTable
            .selectAll()
            .where { OutboxTable.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        OutboxTable.deleteWhere {
            (OutboxTable.state eq state) and (OutboxTable.updatedAt less cutoff)
        }
    }

    override suspend fun deleteExceptMostRecent(state: String, keepCount: Int): Int = newSuspendedTransaction {
        val idsToKeep = OutboxTable
            .select(OutboxTable.id)
            .where { OutboxTable.state eq state }
            .orderBy(OutboxTable.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(keepCount)

        OutboxTable.deleteWhere {
            (OutboxTable.state eq state) and (OutboxTable.id notInSubQuery idsToKeep)
        }
    }

    private fun updateState(id: UUID, newState: String) {
        val now = Clock.System.now()
        OutboxTable.update({ OutboxTable.id eq id }) {
            it[state] = newState
            it[updatedAt] = now
        }
    }

    private fun ResultRow.toOutboxMessage(): OutboxMessage = OutboxMessage(
        id = this[OutboxTable.id].value,
        topic = this[OutboxTable.topic],
        key = this[OutboxTable.key],
        payload = this[OutboxTable.payload],
        state = stringToMessageState(this[OutboxTable.state]),
        attempt = this[OutboxTable.attempt],
        maxAttempts = this[OutboxTable.maxAttempts],
        scheduledAt = this[OutboxTable.scheduledAt],
        createdAt = this[OutboxTable.createdAt],
        updatedAt = this[OutboxTable.updatedAt]
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "sent" -> MessageState.Sent
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
