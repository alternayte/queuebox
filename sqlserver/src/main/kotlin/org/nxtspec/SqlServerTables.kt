package org.nxtspec

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * SQL Server outbox table definition.
 * Uses NVARCHAR(MAX) for JSON payload storage since SQL Server lacks native JSON column type.
 * Note: 'key' is a reserved word in T-SQL, so it must be escaped in raw SQL queries.
 */
object SqlServerOutboxTable : UUIDTable("outbox") {
    val topic: Column<String> = varchar("topic", 255)
    val key: Column<String?> = varchar("key", 255).nullable()
    val payload: Column<String> = text("payload") // JSON stored as NVARCHAR(MAX)
    val headers: Column<String> = text("headers").default("{}") // JSON headers as NVARCHAR(MAX)
    val state: Column<String> = varchar("state", 50).default("pending")
    val attempt: Column<Int> = integer("attempt").default(0)
    val maxAttempts: Column<Int> = integer("max_attempts").default(5)
    val scheduledAt = timestamp("scheduled_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val claimedAt = timestamp("claimed_at").nullable()
    val lastError: Column<String?> = text("last_error").nullable()

    init {
        // Supports the claim seek: state = 'pending' AND scheduled_at <= now().
        index(false, state, scheduledAt)
    }
}

/**
 * SQL Server inbox table definition.
 * Uses NVARCHAR(MAX) for JSON payload storage.
 */
object SqlServerInboxTable : UUIDTable("inbox") {
    val messageSrc: Column<String> = varchar("source", 255)
    val idempotencyKey: Column<String> = varchar("idempotency_key", 255)
    val aggregateId: Column<String?> = varchar("aggregate_id", 255).nullable()
    val eventType: Column<String?> = varchar("event_type", 255).nullable()
    val payload: Column<String> = text("payload") // JSON stored as NVARCHAR(MAX)
    val state: Column<String> = varchar("state", 50).default("pending")
    val createdAt = timestamp("created_at")
    val processedAt = timestamp("processed_at").nullable()
    val claimedAt = timestamp("claimed_at").nullable()
    val correlationId: Column<String?> = varchar("correlation_id", 128).nullable()

    init {
        uniqueIndex(messageSrc, idempotencyKey)
        index(false, aggregateId, state)
        // Supports the claim seek: state = 'pending' ordered by created_at.
        index(false, state, createdAt)
    }
}
