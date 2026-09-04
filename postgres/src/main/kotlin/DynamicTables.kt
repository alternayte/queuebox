package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Dynamic outbox table definition that uses configurable column names.
 * This allows QueueBox to work with existing database schemas that use different column naming conventions.
 *
 * @param mapping The column name mapping configuration
 * @param tableName The name of the table (defaults to "outbox")
 */
class DynamicOutboxTable(
    mapping: OutboxColumnMapping,
    tableName: String = "outbox"
) : UUIDTable(tableName) {
    val topic: Column<String> = varchar(mapping.topic, 255)
    val key: Column<String?> = varchar(mapping.key, 255).nullable()
    val payload: Column<JsonElement> = jsonb(mapping.payload, Json.Default)
    val headers: Column<JsonElement> = jsonb<JsonElement>(mapping.headers, Json.Default).default(JsonObject(emptyMap()))
    val state: Column<String> = varchar(mapping.state, 50).default("pending")
    val attempt: Column<Int> = integer(mapping.attempt).default(0)
    val maxAttempts: Column<Int> = integer(mapping.maxAttempts).default(5)
    val scheduledAt = timestamp(mapping.scheduledAt)
    val createdAt = timestamp(mapping.createdAt)
    val updatedAt = timestamp(mapping.updatedAt)
    val claimedAt = timestamp(mapping.claimedAt).nullable()
    val lastError: Column<String?> = text(mapping.lastError).nullable()

    init {
        index(false, state, scheduledAt)
    }
}

/**
 * Dynamic inbox table definition that uses configurable column names.
 * This allows QueueBox to work with existing database schemas that use different column naming conventions.
 *
 * @param mapping The column name mapping configuration
 * @param tableName The name of the table (defaults to "inbox")
 */
class DynamicInboxTable(
    private val mapping: InboxColumnMapping,
    tableName: String = "inbox"
) : UUIDTable(tableName) {
    val messageSrc: Column<String> = varchar(mapping.source, 255)
    val idempotencyKey: Column<String> = varchar(mapping.idempotencyKey, 255)
    val aggregateId: Column<String?> = varchar(mapping.aggregateId, 255).nullable()
    val eventType: Column<String?> = varchar(mapping.eventType, 255).nullable()
    val payload: Column<JsonElement> = jsonb(mapping.payload, Json.Default)
    val state: Column<String> = varchar(mapping.state, 50).default("pending")
    val createdAt = timestamp(mapping.createdAt)
    val processedAt = timestamp(mapping.processedAt).nullable()
    val claimedAt = timestamp(mapping.claimedAt).nullable()
    val correlationId: Column<String?> = varchar(mapping.correlationId, 128).nullable()

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
        index(false, state, createdAt)
    }
}

/**
 * Factory function to create a DynamicOutboxTable with default column names.
 * Equivalent to the static OutboxTable object.
 */
fun createDefaultOutboxTable(): DynamicOutboxTable = DynamicOutboxTable(OutboxColumnMapping())

/**
 * Factory function to create a DynamicInboxTable with default column names.
 * Equivalent to the static InboxTable object.
 */
fun createDefaultInboxTable(): DynamicInboxTable = DynamicInboxTable(InboxColumnMapping())
