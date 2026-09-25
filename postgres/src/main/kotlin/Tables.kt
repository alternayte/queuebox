package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

object OutboxTable : UUIDTable("outbox") {
    val topic: Column<String> = varchar("topic", 255)
    val key: Column<String?> = varchar("key", 255).nullable()
    val aggregateType: Column<String?> = varchar("aggregate_type", 255).nullable()
    val payload: Column<JsonElement> = jsonb("payload", Json.Default)
    val headers: Column<JsonElement> = jsonb<JsonElement>("headers", Json.Default).default(JsonObject(emptyMap()))
    val state: Column<String> = varchar("state", 50).default("pending")
    val attempt: Column<Int> = integer("attempt").default(0)
    val maxAttempts: Column<Int> = integer("max_attempts").default(5)
    val scheduledAt = timestamp("scheduled_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val claimedAt = timestamp("claimed_at").nullable()
    val claimToken = javaUUID("claim_token").nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val lastError: Column<String?> = text("last_error").nullable()

    // The database fills the sequence on insert. The claim orders the rows of one key by it.
    val sequence: Column<Long> = long("sequence").autoIncrement()

    init {
        index(false, state, scheduledAt)
        index(false, key, sequence)
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
    val claimToken = javaUUID("claim_token").nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val correlationId: Column<String?> = varchar("correlation_id", 128).nullable()
    val headers: Column<JsonElement> = jsonb<JsonElement>("headers", Json.Default).default(JsonObject(emptyMap()))
    val consumption = varchar("consumption", 4).default("push")
    val scheduledAt = timestamp("scheduled_at").clientDefault { kotlin.time.Clock.System.now() }
    val attempt = integer("attempt").default(0)
    val lastError = text("last_error").nullable()

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
        index(false, state, createdAt)
    }
}
