package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.InboxRepositoryInterface
import java.util.UUID

class InboxRepository : InboxRepositoryInterface {
    override suspend fun store(message: InboxMessage): InboxResult = newSuspendedTransaction {
        try {
            val now = Clock.System.now()
            val inserted = InboxTable.insertIgnore {
                it[id] = message.id
                it[messageSrc] = message.source
                it[idempotencyKey] = message.idempotencyKey
                it[eventType] = message.eventType
                it[payload] = message.payload
                it[state] = "pending"
                it[createdAt] = now
            }

            if (inserted.insertedCount == 0) {
                InboxResult.Duplicate
            } else {
                InboxResult.Stored
            }
        } catch (e: Exception) {
            InboxResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun claimPending(batchSize: Int): List<InboxMessage> = newSuspendedTransaction {
        val messages = InboxTable
            .selectAll()
            .where { InboxTable.state eq "pending" }
            .limit(batchSize)
            .forUpdate()
            .map { it.toInboxMessage() }

        if (messages.isNotEmpty()) {
            InboxTable.update({ InboxTable.id inList messages.map { it.id } }) {
                it[state] = "processing"
            }
        }

        messages
    }

    override suspend fun markProcessed(id: UUID): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        InboxTable.update({ InboxTable.id eq id }) {
            it[state] = "processed"
            it[processedAt] = now
        }
        Unit
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        InboxTable
            .selectAll()
            .where { InboxTable.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        InboxTable.deleteWhere {
            (InboxTable.state eq state) and (InboxTable.createdAt less cutoff)
        }
    }

    private fun ResultRow.toInboxMessage(): InboxMessage = InboxMessage(
        id = this[InboxTable.id].value,
        source = this[InboxTable.messageSrc],
        idempotencyKey = this[InboxTable.idempotencyKey],
        eventType = this[InboxTable.eventType],
        payload = this[InboxTable.payload],
        state = stringToMessageState(this[InboxTable.state]),
        createdAt = this[InboxTable.createdAt],
        processedAt = this[InboxTable.processedAt]
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
