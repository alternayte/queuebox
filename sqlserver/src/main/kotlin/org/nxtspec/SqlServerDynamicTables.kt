package org.nxtspec

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Dynamic SQL Server outbox table definition that uses configurable column names.
 * This allows QueueBox to work with existing database schemas that use different column naming conventions.
 *
 * Uses NVARCHAR(MAX) for JSON payload storage since SQL Server lacks native JSON column type.
 * Note: 'key' is a reserved word in T-SQL, so it must be escaped in raw SQL queries when using the default column name.
 *
 * @param mapping The column name mapping configuration
 * @param tableName The name of the table (defaults to "outbox")
 */
class SqlServerDynamicOutboxTable(
    val mapping: OutboxColumnMapping,
    tableName: String = "outbox"
) : UUIDTable(tableName) {
    val topic: Column<String> = varchar(mapping.topic, 255)
    val key: Column<String?> = varchar(mapping.key, 255).nullable()
    val payload: Column<String> = text(mapping.payload)  // JSON stored as NVARCHAR(MAX)
    val headers: Column<String> = text(mapping.headers).default("{}")  // JSON headers as NVARCHAR(MAX)
    val state: Column<String> = varchar(mapping.state, 50).default("pending")
    val attempt: Column<Int> = integer(mapping.attempt).default(0)
    val maxAttempts: Column<Int> = integer(mapping.maxAttempts).default(5)
    val scheduledAt = timestamp(mapping.scheduledAt)
    val createdAt = timestamp(mapping.createdAt)
    val updatedAt = timestamp(mapping.updatedAt)
    val claimedAt = timestamp(mapping.claimedAt).nullable()

    init {
        // Supports the claim seek: state = 'pending' AND scheduled_at <= now().
        index(false, state, scheduledAt)
    }
}

/**
 * Dynamic SQL Server inbox table definition that uses configurable column names.
 * This allows QueueBox to work with existing database schemas that use different column naming conventions.
 *
 * Uses NVARCHAR(MAX) for JSON payload storage.
 *
 * @param mapping The column name mapping configuration
 * @param tableName The name of the table (defaults to "inbox")
 */
class SqlServerDynamicInboxTable(
    val mapping: InboxColumnMapping,
    tableName: String = "inbox"
) : UUIDTable(tableName) {
    val messageSrc: Column<String> = varchar(mapping.source, 255)
    val idempotencyKey: Column<String> = varchar(mapping.idempotencyKey, 255)
    val aggregateId: Column<String?> = varchar(mapping.aggregateId, 255).nullable()
    val eventType: Column<String?> = varchar(mapping.eventType, 255).nullable()
    val payload: Column<String> = text(mapping.payload)  // JSON stored as NVARCHAR(MAX)
    val state: Column<String> = varchar(mapping.state, 50).default("pending")
    val createdAt = timestamp(mapping.createdAt)
    val processedAt = timestamp(mapping.processedAt).nullable()
    val claimedAt = timestamp(mapping.claimedAt).nullable()

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
        // Supports the claim seek: state = 'pending' ordered by created_at.
        index(false, state, createdAt)
    }
}

/**
 * Quotes a SQL Server identifier with square brackets.
 *
 * Every identifier that a raw SQL string interpolates must pass through this function.
 * Quoting protects reserved words such as `key`, and it is the second defence against
 * SQL injection through a configured table name or column name. The first defence is
 * `ConfigValidator`, which rejects an identifier that is not a plain SQL identifier.
 *
 * @param identifier The table name or column name to quote
 * @return The identifier inside square brackets
 */
fun quoteSqlServerIdentifier(identifier: String): String {
    require(!identifier.contains(']')) { "Invalid SQL Server identifier: '$identifier'" }
    return "[$identifier]"
}

/**
 * Factory function to create a SqlServerDynamicOutboxTable with default column names.
 * Equivalent to the static SqlServerOutboxTable object.
 */
fun createDefaultSqlServerOutboxTable(): SqlServerDynamicOutboxTable = SqlServerDynamicOutboxTable(OutboxColumnMapping())

/**
 * Factory function to create a SqlServerDynamicInboxTable with default column names.
 * Equivalent to the static SqlServerInboxTable object.
 */
fun createDefaultSqlServerInboxTable(): SqlServerDynamicInboxTable = SqlServerDynamicInboxTable(InboxColumnMapping())
