package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueueBoxConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig,
    val outbox: OutboxConfig = OutboxConfig(),
    val inbox: InboxConfig = InboxConfig(),
    val destinations: Map<String, DestinationConfig> = emptyMap(),
    val routes: List<RouteConfig> = emptyList(),
    val sources: Map<String, SourceConfig> = emptyMap(),
    val retention: RetentionConfig = RetentionConfig()
)

@Serializable
data class ServerConfig(
    val httpPort: Int = 8080
)

@Serializable
data class DatabaseConfig(
    val type: String = "postgresql",
    val url: String,
    val username: String,
    val password: String,
    val poolSize: Int = 10,
    val connectionTimeoutMs: Long = 30000,
    val columnMapping: ColumnMappingConfig = ColumnMappingConfig(),
    val outboxTableName: String = "outbox",
    val inboxTableName: String = "inbox"
)

/**
 * Configuration for custom column name mappings.
 * Allows QueueBox to work with existing database schemas that use different column naming conventions.
 */
@Serializable
data class ColumnMappingConfig(
    val outbox: OutboxColumnMapping = OutboxColumnMapping(),
    val inbox: InboxColumnMapping = InboxColumnMapping()
)

/**
 * Column name mapping for the outbox table.
 * All defaults match the standard QueueBox column names.
 */
@Serializable
data class OutboxColumnMapping(
    val id: String = "id",
    val topic: String = "topic",
    val key: String = "key",
    val payload: String = "payload",
    val headers: String = "headers",
    val state: String = "state",
    val attempt: String = "attempt",
    val maxAttempts: String = "max_attempts",
    val scheduledAt: String = "scheduled_at",
    val createdAt: String = "created_at",
    val updatedAt: String = "updated_at",
    val claimedAt: String = "claimed_at"
)

/**
 * Column name mapping for the inbox table.
 * All defaults match the standard QueueBox column names.
 */
@Serializable
data class InboxColumnMapping(
    val id: String = "id",
    val source: String = "source",
    val idempotencyKey: String = "idempotency_key",
    val aggregateId: String = "aggregate_id",
    val eventType: String = "event_type",
    val payload: String = "payload",
    val state: String = "state",
    val createdAt: String = "created_at",
    val processedAt: String = "processed_at",
    val claimedAt: String = "claimed_at"
)

@Serializable
data class OutboxConfig(
    val pollIntervalMs: Long = 100,
    val batchSize: Int = 100,
    val retryBaseDelayMs: Long = 1000,
    val maxAttempts: Int = 5,
    /** Visibility timeout. A claim older than this returns to state 'pending'. See F-006. */
    val claimTimeoutMs: Long = 300000
)

@Serializable
data class InboxConfig(
    val basePath: String = "/inbox",
    val relay: InboxRelayConfig = InboxRelayConfig()
)

/**
 * Configuration for the inbox relay.
 *
 * The relay moves a stored inbox message into the outbox table, and the outbox machinery
 * routes, transforms and delivers it. QueueBox runs no business logic on the message. See F-002.
 */
@Serializable
data class InboxRelayConfig(
    val enabled: Boolean = true,
    val pollIntervalMs: Long = 100,
    val batchSize: Int = 100,
    /** Visibility timeout. A claim older than this returns to state 'pending'. See F-006. */
    val claimTimeoutMs: Long = 300000
)

@Serializable
sealed class DestinationConfig {
    abstract val transform: TransformConfig?

    @Serializable
    @SerialName("http")
    data class Http(
        val baseUrl: String,
        val path: String = "/",
        val timeoutMs: Long = 30000,
        val headers: Map<String, String> = emptyMap(),
        override val transform: TransformConfig? = null,
        val auth: DestinationAuthConfig? = null
    ) : DestinationConfig()

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val url: String,
        val exchange: String,
        val exchangeType: String = "topic",
        val headers: Map<String, String> = emptyMap(),
        override val transform: TransformConfig? = null
    ) : DestinationConfig()
}

@Serializable
data class RouteConfig(
    val topicPattern: String,
    val destination: String,
    val routingKeyTemplate: String? = null,
    val routingKeyMissingFieldDefault: String? = null,
    val transform: TransformConfig? = null
)

@Serializable
sealed class SourceConfig {
    abstract val transform: TransformConfig?

    /**
     * Template for the outbox topic that the relay writes. Supports `{{ source }}` and
     * `{{ eventType }}`. See F-002.
     */
    abstract val topic: String

    @Serializable
    @SerialName("http")
    data class Http(
        val path: String,
        val idempotencyKeyPath: String,
        val aggregateIdPath: String? = null,
        val eventTypePath: String? = null,
        override val transform: TransformConfig? = null,
        override val topic: String = "{{ eventType }}",
        val auth: InboxAuthConfig? = null
    ) : SourceConfig()

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val queueName: String,
        val connectionUrl: String,
        val idempotencyKeyPath: String = "$.id",
        val aggregateIdPath: String? = null,
        val prefetchCount: Int = 10,
        override val transform: TransformConfig? = null,
        override val topic: String = "{{ eventType }}"
    ) : SourceConfig()
}
