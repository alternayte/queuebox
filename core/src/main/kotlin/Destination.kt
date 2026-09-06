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

    /**
     * A Kafka topic.
     *
     * `bootstrapServers` can carry no credential, so it needs no mask. A SASL password does,
     * and it lives in `saslPassword`, which is a `Secret`.
     */
    @Serializable
    @SerialName("kafka")
    data class Kafka(
        val name: String,
        val bootstrapServers: String,
        val topic: String,
        /** The record key. `{{ topic }}` and `{{ key }}` render from the outbox row. */
        val keyTemplate: String = "{{ key }}",
        val headers: Map<String, String> = emptyMap(),
        val securityProtocol: String = "PLAINTEXT",
        val saslMechanism: String? = null,
        val saslUsername: String? = null,
        val saslPassword: Secret? = null,
        /** How long one publish may take, including the broker acknowledgement. */
        val timeoutMs: Long = 30000
    ) : Destination {
        override fun toString(): String = "Kafka(name=$name, bootstrapServers=$bootstrapServers, " +
            "topic=$topic, keyTemplate=$keyTemplate, headers=${CredentialMasking.maskHeaders(headers)}, " +
            "securityProtocol=$securityProtocol, saslMechanism=$saslMechanism, saslUsername=$saslUsername)"
    }

    /**
     * A NATS subject.
     *
     * `jetStream` decides what a successful publish means. With JetStream the broker answers
     * with an acknowledgement, so the outbox marks the row sent only after the message is
     * durable. Core NATS has no acknowledgement at all: the publish is fire and forget, and a
     * message can vanish with no error. Keep JetStream unless the subject is genuinely a
     * best-effort signal.
     */
    @Serializable
    @SerialName("nats")
    data class Nats(
        val name: String,
        val servers: String,
        val subject: String,
        val jetStream: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val username: String? = null,
        val password: Secret? = null,
        val token: Secret? = null,
        val timeoutMs: Long = 30000
    ) : Destination {
        override fun toString(): String = "Nats(name=$name, servers=${CredentialMasking.maskUrl(servers)}, " +
            "subject=$subject, jetStream=$jetStream, headers=${CredentialMasking.maskHeaders(headers)}, " +
            "username=$username, timeoutMs=$timeoutMs)"
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
