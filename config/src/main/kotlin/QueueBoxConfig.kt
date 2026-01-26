package org.nxtspec

import kotlinx.serialization.Serializable


@Serializable
data class QueueBoxConfig(
    val server: ServerConfig = ServerConfig(),
    val outbox: OutboxConfig = OutboxConfig(),
    val inbox: InboxConfig = InboxConfig()
)

@Serializable
data class ServerConfig(
    val httpPort: Int = 8080
)

@Serializable
data class OutboxConfig(
    val destinations: Map<String, RabbitConfig> = emptyMap()
)

@Serializable
data class RabbitConfig(
    val url: String,
    val exchange: String
)

@Serializable
data class InboxConfig(
    val httpPath: String = "/inbox"
)

