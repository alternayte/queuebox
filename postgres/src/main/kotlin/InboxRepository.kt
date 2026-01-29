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

class InboxRepository(
    private val columnMapping: InboxColumnMapping = InboxColumnMapping(),
    private val tableName: String = "inbox"
) : InboxRepositoryInterface {
    private val table = DynamicInboxTable(columnMapping, tableName)
    override suspend fun store(message: InboxMessage): InboxResult = newSuspendedTransaction {
        try {
            val now = Clock.System.now()
            val inserted = table.insertIgnore {
                it[id] = message.id
                it[messageSrc] = message.source
                it[idempotencyKey] = message.idempotencyKey
                it[aggregateId] = message.aggregateId
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
        // Use CTE-based query for aggregate ordering:
        // - Only claims one message per aggregate at a time (oldest first)
        // - Messages without aggregateId are treated as independent
        // - Excludes aggregates that already have messages being processed
        val sql = """
            WITH
            locked_aggregates AS (
                SELECT DISTINCT ${columnMapping.aggregateId}
                FROM $tableName
                WHERE ${columnMapping.aggregateId} IS NOT NULL
                AND ${columnMapping.state} = 'processing'
            ),
            aggregate_messages AS (
                SELECT DISTINCT ON (${columnMapping.aggregateId}) *
                FROM $tableName
                WHERE ${columnMapping.aggregateId} IS NOT NULL
                AND ${columnMapping.state} = 'pending'
                AND ${columnMapping.aggregateId} NOT IN (SELECT ${columnMapping.aggregateId} FROM locked_aggregates WHERE ${columnMapping.aggregateId} IS NOT NULL)
                ORDER BY ${columnMapping.aggregateId}, ${columnMapping.createdAt} ASC
            ),
            independent_messages AS (
                SELECT * FROM $tableName
                WHERE ${columnMapping.aggregateId} IS NULL
                AND ${columnMapping.state} = 'pending'
            ),
            candidates AS (
                SELECT * FROM aggregate_messages
                UNION ALL
                SELECT * FROM independent_messages
            )
            SELECT ${columnMapping.id}, ${columnMapping.source}, ${columnMapping.idempotencyKey}, ${columnMapping.aggregateId}, ${columnMapping.eventType}, ${columnMapping.payload}, ${columnMapping.state}, ${columnMapping.createdAt}, ${columnMapping.processedAt}
            FROM candidates
            ORDER BY ${columnMapping.createdAt} ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection.connection as java.sql.Connection
        val messages = conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, batchSize)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<InboxMessage>()
                while (rs.next()) {
                    results.add(rs.toInboxMessageFromResultSet())
                }
                results
            }
        }

        if (messages.isNotEmpty()) {
            table.update({ table.id inList messages.map { it.id } }) {
                it[state] = "processing"
            }
        }

        messages
    }

    override suspend fun markProcessed(id: UUID): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[state] = "processed"
            it[processedAt] = now
        }
        Unit
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        table.deleteWhere {
            (table.state eq state) and (table.createdAt less cutoff)
        }
    }

    private fun ResultRow.toInboxMessage(): InboxMessage = InboxMessage(
        id = this[table.id].value,
        source = this[table.messageSrc],
        idempotencyKey = this[table.idempotencyKey],
        aggregateId = this[table.aggregateId],
        eventType = this[table.eventType],
        payload = this[table.payload],
        state = stringToMessageState(this[table.state]),
        createdAt = this[table.createdAt],
        processedAt = this[table.processedAt]
    )

    private fun java.sql.ResultSet.toInboxMessageFromResultSet(): InboxMessage = InboxMessage(
        id = java.util.UUID.fromString(getString(columnMapping.id)),
        source = getString(columnMapping.source),
        idempotencyKey = getString(columnMapping.idempotencyKey),
        aggregateId = getString(columnMapping.aggregateId),
        eventType = getString(columnMapping.eventType),
        payload = kotlinx.serialization.json.Json.parseToJsonElement(getString(columnMapping.payload)),
        state = stringToMessageState(getString(columnMapping.state)),
        createdAt = getTimestamp(columnMapping.createdAt).toInstant().let {
            kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
        },
        processedAt = getTimestamp(columnMapping.processedAt)?.toInstant()?.let {
            kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
        }
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
