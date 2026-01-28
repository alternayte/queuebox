package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.InboxRepositoryInterface
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * SQL Server implementation of the inbox repository.
 * Uses MERGE statement for atomic insert-if-not-exists deduplication,
 * which is the SQL Server equivalent of PostgreSQL's INSERT ON CONFLICT IGNORE.
 */
class SqlServerInboxRepository : InboxRepositoryInterface {

    override suspend fun store(message: InboxMessage): InboxResult = newSuspendedTransaction {
        try {
            val now = Clock.System.now()
            val nowTimestamp = Timestamp.from(
                java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
            )

            // Use MERGE for atomic insert-if-not-exists
            // This is the SQL Server equivalent of INSERT ... ON CONFLICT DO NOTHING
            val sql = """
                MERGE inbox AS target
                USING (SELECT ? AS source, ? AS idempotency_key) AS src
                ON target.source = src.source AND target.idempotency_key = src.idempotency_key
                WHEN NOT MATCHED THEN
                    INSERT (id, source, idempotency_key, event_type, payload, state, created_at)
                    VALUES (?, ?, ?, ?, ?, 'pending', ?);
            """.trimIndent()

            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            val rowsAffected = conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, message.source)
                stmt.setString(2, message.idempotencyKey)
                stmt.setString(3, message.id.toString())
                stmt.setString(4, message.source)
                stmt.setString(5, message.idempotencyKey)
                stmt.setString(6, message.eventType)
                stmt.setString(7, message.payload.toString())
                stmt.setTimestamp(8, nowTimestamp)
                stmt.executeUpdate()
            }

            if (rowsAffected == 0) {
                InboxResult.Duplicate
            } else {
                InboxResult.Stored
            }
        } catch (e: Exception) {
            InboxResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun claimPending(batchSize: Int): List<InboxMessage> = newSuspendedTransaction {
        // Use raw SQL with SQL Server-specific locking hints
        val sql = """
            SELECT TOP (?) id, source, idempotency_key, event_type, payload, state, created_at, processed_at
            FROM inbox WITH (ROWLOCK, UPDLOCK, READPAST)
            WHERE state = 'pending'
        """.trimIndent()

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val messages = conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, batchSize)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<InboxMessage>()
                while (rs.next()) {
                    results.add(rs.toInboxMessage())
                }
                results
            }
        }

        if (messages.isNotEmpty()) {
            SqlServerInboxTable.update({ SqlServerInboxTable.id inList messages.map { it.id } }) {
                it[state] = "processing"
            }
        }

        messages
    }

    override suspend fun markProcessed(id: UUID): Unit = newSuspendedTransaction {
        val now = Clock.System.now()
        SqlServerInboxTable.update({ SqlServerInboxTable.id eq id }) {
            it[state] = "processed"
            it[processedAt] = now
        }
        Unit
    }

    override suspend fun countByState(state: String): Long = newSuspendedTransaction {
        SqlServerInboxTable
            .selectAll()
            .where { SqlServerInboxTable.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant): Int = newSuspendedTransaction {
        SqlServerInboxTable.deleteWhere {
            (SqlServerInboxTable.state eq state) and (SqlServerInboxTable.createdAt less cutoff)
        }
    }

    private fun ResultRow.toInboxMessage(): InboxMessage = InboxMessage(
        id = this[SqlServerInboxTable.id].value,
        source = this[SqlServerInboxTable.messageSrc],
        idempotencyKey = this[SqlServerInboxTable.idempotencyKey],
        eventType = this[SqlServerInboxTable.eventType],
        payload = Json.parseToJsonElement(this[SqlServerInboxTable.payload]),
        state = stringToMessageState(this[SqlServerInboxTable.state]),
        createdAt = this[SqlServerInboxTable.createdAt],
        processedAt = this[SqlServerInboxTable.processedAt]
    )

    private fun ResultSet.toInboxMessage(): InboxMessage = InboxMessage(
        id = UUID.fromString(getString("id")),
        source = getString("source"),
        idempotencyKey = getString("idempotency_key"),
        eventType = getString("event_type"),
        payload = Json.parseToJsonElement(getString("payload")),
        state = stringToMessageState(getString("state")),
        createdAt = getTimestamp("created_at").toInstant().toKotlinInstant(),
        processedAt = getTimestamp("processed_at")?.toInstant()?.toKotlinInstant()
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
