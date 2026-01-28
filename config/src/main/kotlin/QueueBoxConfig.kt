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
    val sources: Map<String, SourceConfig> = emptyMap()
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
    val connectionTimeoutMs: Long = 30000
)

@Serializable
data class OutboxConfig(
    val pollIntervalMs: Long = 100,
    val batchSize: Int = 100,
    val retryBaseDelayMs: Long = 1000,
    val maxAttempts: Int = 5
)

@Serializable
data class InboxConfig(
    val basePath: String = "/inbox"
)

@Serializable
sealed class DestinationConfig {
    @Serializable
    @SerialName("http")
    data class Http(
        val baseUrl: String,
        val path: String = "/",
        val timeoutMs: Long = 30000,
        val headers: Map<String, String> = emptyMap()
    ) : DestinationConfig()

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val url: String,
        val exchange: String,
        val exchangeType: String = "topic"
    ) : DestinationConfig()
}

@Serializable
data class RouteConfig(
    val topicPattern: String,
    val destination: String,
    val routingKeyTemplate: String? = null
)

@Serializable
sealed class SourceConfig {
    @Serializable
    @SerialName("http")
    data class Http(
        val path: String,
        val idempotencyKeyPath: String,
        val eventTypePath: String? = null
    ) : SourceConfig()

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val queueName: String,
        val connectionUrl: String,
        val idempotencyKeyPath: String = "$.id",
        val prefetchCount: Int = 10
    ) : SourceConfig()
}
