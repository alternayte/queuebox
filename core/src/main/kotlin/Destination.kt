package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Destination {
    @Serializable
    @SerialName("http")
    data class Http(
        val name: String,
        val baseUrl: String,
        val path: String = "/",
        val timeoutMs: Long = 30000,
        val headers: Map<String, String> = emptyMap()
    ) : Destination

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val name: String,
        val url: String,
        val exchange: String,
        val exchangeType: String = "topic",
        val routingKeyTemplate: String = "{{ topic }}"
    ) : Destination
}
