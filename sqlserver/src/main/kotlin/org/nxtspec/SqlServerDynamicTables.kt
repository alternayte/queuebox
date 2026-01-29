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

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
    }
}

/**
 * SQL Server reserved words that need to be escaped with brackets in raw SQL queries.
 */
private val SQL_SERVER_RESERVED_WORDS = setOf(
    "key", "user", "order", "group", "table", "index", "column", "select", "insert",
    "update", "delete", "from", "where", "join", "left", "right", "inner", "outer",
    "on", "and", "or", "not", "null", "in", "between", "like", "is", "as", "by",
    "asc", "desc", "distinct", "top", "with", "case", "when", "then", "else", "end"
)

/**
 * Escapes a column name with brackets if it's a SQL Server reserved word.
 * This prevents syntax errors when using reserved words as column names in raw SQL.
 *
 * @param columnName The column name to potentially escape
 * @return The escaped column name if it's a reserved word, otherwise the original name
 */
fun escapeSqlServerColumnName(columnName: String): String {
    return if (columnName.lowercase() in SQL_SERVER_RESERVED_WORDS) {
        "[$columnName]"
    } else {
        columnName
    }
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
