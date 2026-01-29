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
class SqlServerInboxRepository(
    private val columnMapping: InboxColumnMapping = InboxColumnMapping(),
    private val tableName: String = "inbox"
) : InboxRepositoryInterface {
    private val table = SqlServerDynamicInboxTable(columnMapping, tableName)

    override suspend fun store(message: InboxMessage): InboxResult = newSuspendedTransaction {
        try {
            val now = Clock.System.now()
            val nowTimestamp = Timestamp.from(
                java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
            )

            // Escape column names that are SQL Server reserved words
            val sourceCol = escapeSqlServerColumnName(columnMapping.source)
            val idempotencyKeyCol = escapeSqlServerColumnName(columnMapping.idempotencyKey)
            val idCol = escapeSqlServerColumnName(columnMapping.id)
            val aggregateIdCol = escapeSqlServerColumnName(columnMapping.aggregateId)
            val eventTypeCol = escapeSqlServerColumnName(columnMapping.eventType)
            val payloadCol = escapeSqlServerColumnName(columnMapping.payload)
            val stateCol = escapeSqlServerColumnName(columnMapping.state)
            val createdAtCol = escapeSqlServerColumnName(columnMapping.createdAt)

            // Use MERGE for atomic insert-if-not-exists
            // This is the SQL Server equivalent of INSERT ... ON CONFLICT DO NOTHING
            val sql = """
                MERGE $tableName AS target
                USING (SELECT ? AS source, ? AS idempotency_key) AS src
                ON target.$sourceCol = src.source AND target.$idempotencyKeyCol = src.idempotency_key
                WHEN NOT MATCHED THEN
                    INSERT ($idCol, $sourceCol, $idempotencyKeyCol, $aggregateIdCol, $eventTypeCol, $payloadCol, $stateCol, $createdAtCol)
                    VALUES (?, ?, ?, ?, ?, ?, 'pending', ?);
            """.trimIndent()

            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            val rowsAffected = conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, message.source)
                stmt.setString(2, message.idempotencyKey)
                stmt.setString(3, message.id.toString())
                stmt.setString(4, message.source)
                stmt.setString(5, message.idempotencyKey)
                stmt.setString(6, message.aggregateId)
                stmt.setString(7, message.eventType)
                stmt.setString(8, message.payload.toString())
                stmt.setTimestamp(9, nowTimestamp)
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
        // Escape column names that are SQL Server reserved words
        val idCol = escapeSqlServerColumnName(columnMapping.id)
        val sourceCol = escapeSqlServerColumnName(columnMapping.source)
        val idempotencyKeyCol = escapeSqlServerColumnName(columnMapping.idempotencyKey)
        val aggregateIdCol = escapeSqlServerColumnName(columnMapping.aggregateId)
        val eventTypeCol = escapeSqlServerColumnName(columnMapping.eventType)
        val payloadCol = escapeSqlServerColumnName(columnMapping.payload)
        val stateCol = escapeSqlServerColumnName(columnMapping.state)
        val createdAtCol = escapeSqlServerColumnName(columnMapping.createdAt)
        val processedAtCol = escapeSqlServerColumnName(columnMapping.processedAt)

        // Use CTE-based query with ROW_NUMBER() for aggregate ordering:
        // - Only claims one message per aggregate at a time (oldest first)
        // - Messages without aggregateId are treated as independent
        // - Excludes aggregates that already have messages being processed
        val sql = """
            WITH
            locked_aggregates AS (
                SELECT DISTINCT $aggregateIdCol
                FROM $tableName
                WHERE $aggregateIdCol IS NOT NULL
                AND $stateCol = 'processing'
            ),
            aggregate_messages AS (
                SELECT *, ROW_NUMBER() OVER (PARTITION BY $aggregateIdCol ORDER BY $createdAtCol ASC) as rn
                FROM $tableName
                WHERE $aggregateIdCol IS NOT NULL
                AND $stateCol = 'pending'
                AND $aggregateIdCol NOT IN (SELECT $aggregateIdCol FROM locked_aggregates WHERE $aggregateIdCol IS NOT NULL)
            ),
            independent_messages AS (
                SELECT *, 1 as rn
                FROM $tableName
                WHERE $aggregateIdCol IS NULL
                AND $stateCol = 'pending'
            ),
            candidates AS (
                SELECT $idCol, $sourceCol, $idempotencyKeyCol, $aggregateIdCol, $eventTypeCol, $payloadCol, $stateCol, $createdAtCol, $processedAtCol
                FROM aggregate_messages WHERE rn = 1
                UNION ALL
                SELECT $idCol, $sourceCol, $idempotencyKeyCol, $aggregateIdCol, $eventTypeCol, $payloadCol, $stateCol, $createdAtCol, $processedAtCol
                FROM independent_messages
            )
            SELECT TOP (?) $idCol, $sourceCol, $idempotencyKeyCol, $aggregateIdCol, $eventTypeCol, $payloadCol, $stateCol, $createdAtCol, $processedAtCol
            FROM candidates WITH (ROWLOCK, UPDLOCK, READPAST)
            ORDER BY $createdAtCol ASC
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

    private fun ResultRow.toInboxMessageFromRow(): InboxMessage = InboxMessage(
        id = this[table.id].value,
        source = this[table.messageSrc],
        idempotencyKey = this[table.idempotencyKey],
        aggregateId = this[table.aggregateId],
        eventType = this[table.eventType],
        payload = Json.parseToJsonElement(this[table.payload]),
        state = stringToMessageState(this[table.state]),
        createdAt = this[table.createdAt],
        processedAt = this[table.processedAt]
    )

    private fun ResultSet.toInboxMessage(): InboxMessage = InboxMessage(
        id = UUID.fromString(getString(columnMapping.id)),
        source = getString(columnMapping.source),
        idempotencyKey = getString(columnMapping.idempotencyKey),
        aggregateId = getString(columnMapping.aggregateId),
        eventType = getString(columnMapping.eventType),
        payload = Json.parseToJsonElement(getString(columnMapping.payload)),
        state = stringToMessageState(getString(columnMapping.state)),
        createdAt = getTimestamp(columnMapping.createdAt).toInstant().toKotlinInstant(),
        processedAt = getTimestamp(columnMapping.processedAt)?.toInstant()?.toKotlinInstant()
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }
}
