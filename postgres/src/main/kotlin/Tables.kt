package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object OutboxTable : UUIDTable("outbox") {
    val topic: Column<String> = varchar("topic", 255)
    val key: Column<String?> = varchar("key", 255).nullable()
    val payload: Column<JsonElement> = jsonb("payload", Json.Default)
    val headers: Column<JsonElement> = jsonb<JsonElement>("headers", Json.Default).default(JsonObject(emptyMap()))
    val state: Column<String> = varchar("state", 50).default("pending")
    val attempt: Column<Int> = integer("attempt").default(0)
    val maxAttempts: Column<Int> = integer("max_attempts").default(5)
    val scheduledAt = timestamp("scheduled_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val claimedAt = timestamp("claimed_at").nullable()

    init {
        index(false, state, scheduledAt)
    }
}

object InboxTable : UUIDTable("inbox") {
    val messageSrc: Column<String> = varchar("source", 255)
    val idempotencyKey: Column<String> = varchar("idempotency_key", 255)
    val aggregateId: Column<String?> = varchar("aggregate_id", 255).nullable()
    val eventType: Column<String?> = varchar("event_type", 255).nullable()
    val payload: Column<JsonElement> = jsonb("payload", Json.Default)
    val state: Column<String> = varchar("state", 50).default("pending")
    val createdAt = timestamp("created_at")
    val processedAt = timestamp("processed_at").nullable()
    val claimedAt = timestamp("claimed_at").nullable()

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
        index(false, state, createdAt)
    }
}
