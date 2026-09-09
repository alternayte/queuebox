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
        /**
         * The topic name, or a template that renders one topic name per row. F-091. A template
         * can read `{{ topic }}`, `{{ key }}`, `{{ aggregateType }}`, or a payload field. A
         * publisher must reject a row whose rendered topic is empty.
         */
        val topic: String,
        /** The record key. `{{ topic }}` and `{{ key }}` render from the outbox row. */
        val keyTemplate: String = "{{ key }}",
        val headers: Map<String, String> = emptyMap(),
        val securityProtocol: String = "PLAINTEXT",
        val saslMechanism: String? = null,
        val saslUsername: String? = null,
        val saslPassword: Secret? = null,
        /** How long one publish may take, including the broker acknowledgement. */
        val timeoutMs: Long = 30000,
        /**
         * The name of a row column to read the topic name from, verbatim. F-091. A value here
         * wins over [topic], and the router does not render the template at all. The permitted
         * names are exactly the set in [PERMITTED_ADDRESS_FROM_COLUMNS].
         */
        val topicFrom: String? = null
    ) : Destination {
        override fun toString(): String = "Kafka(name=$name, bootstrapServers=$bootstrapServers, " +
            "topic=$topic, keyTemplate=$keyTemplate, headers=${CredentialMasking.maskHeaders(headers)}, " +
            "securityProtocol=$securityProtocol, saslMechanism=$saslMechanism, saslUsername=$saslUsername, " +
            "topicFrom=$topicFrom)"
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
        /**
         * The subject name, or a template that renders one subject name per row. F-091. A
         * template can read `{{ topic }}`, `{{ key }}`, `{{ aggregateType }}`, or a payload
         * field. A publisher must reject a row whose rendered subject is empty.
         */
        val subject: String,
        val jetStream: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val username: String? = null,
        val password: Secret? = null,
        val token: Secret? = null,
        val timeoutMs: Long = 30000,
        /**
         * The name of a row column to read the subject name from, verbatim. F-091. A value here
         * wins over [subject], and the router does not render the template at all. The permitted
         * names are exactly the set in [PERMITTED_ADDRESS_FROM_COLUMNS].
         */
        val subjectFrom: String? = null
    ) : Destination {
        override fun toString(): String = "Nats(name=$name, servers=${CredentialMasking.maskUrl(servers)}, " +
            "subject=$subject, jetStream=$jetStream, headers=${CredentialMasking.maskHeaders(headers)}, " +
            "username=$username, timeoutMs=$timeoutMs, subjectFrom=$subjectFrom)"
    }

    @Serializable
    @SerialName("rabbitmq")
    data class RabbitMQ(
        val name: String,
        val url: String,
        /**
         * The exchange name, or a template that renders one exchange name per row. F-091. A
         * template can read `{{ topic }}`, `{{ key }}`, `{{ aggregateType }}`, or a payload
         * field. A publisher must reject a row whose rendered exchange is empty, and it must
         * never fall back to the AMQP default exchange.
         */
        val exchange: String,
        val exchangeType: String = "topic",
        val routingKeyTemplate: String = "{{ topic }}",
        val headers: Map<String, String> = emptyMap(),
        /**
         * The name of a row column to read the exchange name from, verbatim. F-091. A value here
         * wins over [exchange], and the router does not render the template at all. The
         * permitted names are exactly the set in [PERMITTED_ADDRESS_FROM_COLUMNS]: these three
         * are routing fields that an application sets deliberately, and a wider set would let a
         * broker name come from data that was never meant for routing, for example the payload.
         */
        val exchangeFrom: String? = null
    ) : Destination {
        /**
         * F-038: an AMQP URI carries the broker password, so the printed form masks it.
         */
        override fun toString(): String = "RabbitMQ(name=$name, url=${CredentialMasking.maskUrl(url)}, " +
            "exchange=$exchange, exchangeType=$exchangeType, routingKeyTemplate=$routingKeyTemplate, " +
            "headers=${CredentialMasking.maskHeaders(headers)}, exchangeFrom=$exchangeFrom)"
    }

    companion object {
        /**
         * How [RabbitMQ.exchangeFrom], [Kafka.topicFrom], and [Nats.subjectFrom] each read a
         * permitted column out of a row. This map is the single source of both the resolution
         * behaviour and the permitted column names below, so a name can never appear in one
         * without appearing in the other. F-091.
         */
        private val ADDRESS_FROM_COLUMN_ACCESSORS: Map<String, (OutboxMessage) -> String?> = mapOf(
            "aggregate_type" to { row -> row.aggregateType },
            "topic" to { row -> row.topic },
            "key" to { row -> row.key }
        )

        /**
         * The row columns that [RabbitMQ.exchangeFrom], [Kafka.topicFrom], and [Nats.subjectFrom]
         * can name. F-091. A startup validator consults exactly this set, rather than carrying
         * its own copy, so the permitted names stay in one place.
         */
        val PERMITTED_ADDRESS_FROM_COLUMNS: Set<String> = ADDRESS_FROM_COLUMN_ACCESSORS.keys

        /**
         * Reads the named column from a row, verbatim, for [RabbitMQ.exchangeFrom],
         * [Kafka.topicFrom], or [Nats.subjectFrom]. F-091. Returns null when the column name is
         * outside the permitted set, or when the column value itself is null.
         */
        fun readAddressFromColumn(column: String, row: OutboxMessage): String? =
            ADDRESS_FROM_COLUMN_ACCESSORS[column]?.invoke(row)
    }
}
