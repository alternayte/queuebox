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
        val headers: Map<String, String> = emptyMap(),
        val authConfig: DestinationAuthConfig? = null
    ) : Destination {
        /**
         * F-038: a URL can carry user information, and a static header can carry a token. The
         * configuration twin of this class masks both, and so must the domain class.
         */
        override fun toString(): String = "Http(name=$name, baseUrl=${CredentialMasking.maskUrl(baseUrl)}, " +
            "path=$path, timeoutMs=$timeoutMs, headers=${CredentialMasking.maskHeaders(headers)}, " +
            "authConfig=$authConfig)"
    }

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val name: String,
        val url: String,
        val exchange: String,
        val exchangeType: String = "topic",
        val routingKeyTemplate: String = "{{ topic }}",
        val headers: Map<String, String> = emptyMap()
    ) : Destination {
        /**
         * F-038: an AMQP URI carries the broker password, so the printed form masks it.
         */
        override fun toString(): String = "RabbitMQ(name=$name, url=${CredentialMasking.maskUrl(url)}, " +
            "exchange=$exchange, exchangeType=$exchangeType, routingKeyTemplate=$routingKeyTemplate, " +
            "headers=${CredentialMasking.maskHeaders(headers)})"
    }
}
