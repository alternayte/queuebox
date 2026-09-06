package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The Kafka and NATS rules of the configuration.
 *
 * Every rule here exists because the broker client would otherwise fail later and less clearly:
 * at the first publish, at the first connection, or not at all.
 */
class BrokerConfigValidationTest {

    private fun kafkaDestination(
        bootstrapServers: String = "localhost:9092",
        topic: String = "events",
        timeoutMs: Long = 30000,
        securityProtocol: String = "PLAINTEXT",
        saslMechanism: String? = null,
        saslUsername: String? = null,
        saslPassword: Secret? = null
    ) = DestinationConfig.Kafka(
        bootstrapServers = bootstrapServers,
        topic = topic,
        timeoutMs = timeoutMs,
        securityProtocol = securityProtocol,
        saslMechanism = saslMechanism,
        saslUsername = saslUsername,
        saslPassword = saslPassword
    )

    private fun kafkaSource() = SourceConfig.Kafka(
        bootstrapServers = "localhost:9092",
        topics = listOf("orders"),
        groupId = "queuebox"
    )

    @Test
    fun `a complete kafka destination and source pass`() {
        validateKafkaDestination("events", kafkaDestination())
        validateKafkaSource("orders", kafkaSource())
    }

    @Test
    fun `a kafka destination refuses missing or impossible settings`() {
        assertFailsWith<IllegalArgumentException> {
            validateKafkaDestination("e", kafkaDestination(bootstrapServers = ""))
        }
        assertFailsWith<IllegalArgumentException> { validateKafkaDestination("e", kafkaDestination(topic = "")) }
        // Below this the producer cannot keep its own rule that the delivery timeout stays
        // above the request timeout.
        assertFailsWith<IllegalArgumentException> { validateKafkaDestination("e", kafkaDestination(timeoutMs = 1)) }
        assertFailsWith<IllegalArgumentException> {
            validateKafkaDestination("e", kafkaDestination(securityProtocol = "PLAIN"))
        }
    }

    @Test
    fun `a kafka source refuses missing or impossible settings`() {
        assertFailsWith<IllegalArgumentException> {
            validateKafkaSource("o", kafkaSource().copy(bootstrapServers = ""))
        }
        assertFailsWith<IllegalArgumentException> { validateKafkaSource("o", kafkaSource().copy(topics = emptyList())) }
        assertFailsWith<IllegalArgumentException> { validateKafkaSource("o", kafkaSource().copy(topics = listOf(" "))) }
        assertFailsWith<IllegalArgumentException> { validateKafkaSource("o", kafkaSource().copy(groupId = "")) }
        assertFailsWith<IllegalArgumentException> {
            validateKafkaSource("o", kafkaSource().copy(autoOffsetReset = "none"))
        }
        assertFailsWith<IllegalArgumentException> { validateKafkaSource("o", kafkaSource().copy(maxPollRecords = 0)) }
    }

    @Test
    fun `a SASL protocol needs a mechanism and a credential`() {
        assertFailsWith<IllegalArgumentException> {
            validateKafkaDestination("e", kafkaDestination(securityProtocol = "SASL_PLAINTEXT"))
        }
        assertFailsWith<IllegalArgumentException> {
            validateKafkaDestination(
                "e",
                kafkaDestination(securityProtocol = "SASL_SSL", saslMechanism = "PLAIN")
            )
        }
        // A complete SASL destination passes.
        validateKafkaDestination(
            "e",
            kafkaDestination(
                securityProtocol = "SASL_SSL",
                saslMechanism = "SCRAM-SHA-512",
                saslUsername = "user",
                saslPassword = Secret("secret")
            )
        )
        validateKafkaSource(
            "o",
            kafkaSource().copy(
                securityProtocol = "SASL_PLAINTEXT",
                saslMechanism = "PLAIN",
                saslUsername = "user",
                saslPassword = Secret("secret")
            )
        )
    }

    private fun natsDestination(
        servers: String = "nats://localhost:4222",
        subject: String = "events.created",
        timeoutMs: Long = 30000,
        username: String? = null,
        password: Secret? = null,
        token: Secret? = null
    ) = DestinationConfig.Nats(
        servers = servers,
        subject = subject,
        timeoutMs = timeoutMs,
        username = username,
        password = password,
        token = token
    )

    private fun natsSource() = SourceConfig.Nats(
        servers = "nats://localhost:4222",
        stream = "ORDERS",
        durable = "queuebox"
    )

    @Test
    fun `a complete nats destination and source pass`() {
        validateNatsDestination("events", natsDestination())
        validateNatsSource("orders", natsSource())
    }

    @Test
    fun `a nats destination refuses missing settings`() {
        assertFailsWith<IllegalArgumentException> { validateNatsDestination("e", natsDestination(servers = "")) }
        assertFailsWith<IllegalArgumentException> { validateNatsDestination("e", natsDestination(subject = "")) }
        assertFailsWith<IllegalArgumentException> { validateNatsDestination("e", natsDestination(timeoutMs = 0)) }
    }

    @Test
    fun `a nats source refuses missing settings`() {
        assertFailsWith<IllegalArgumentException> { validateNatsSource("o", natsSource().copy(servers = "")) }
        // QueueBox consumes a stream and never creates one.
        assertFailsWith<IllegalArgumentException> { validateNatsSource("o", natsSource().copy(stream = "")) }
        // A durable consumer is what keeps the position across a restart.
        assertFailsWith<IllegalArgumentException> { validateNatsSource("o", natsSource().copy(durable = "")) }
        assertFailsWith<IllegalArgumentException> { validateNatsSource("o", natsSource().copy(ackWaitMs = 0)) }
        assertFailsWith<IllegalArgumentException> { validateNatsSource("o", natsSource().copy(batchSize = 0)) }
    }

    @Test
    fun `nats credentials must be complete and must not conflict`() {
        assertFailsWith<IllegalArgumentException> { validateNatsDestination("e", natsDestination(username = "user")) }
        assertFailsWith<IllegalArgumentException> {
            validateNatsDestination("e", natsDestination(password = Secret("secret")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateNatsSource(
                "o",
                natsSource().copy(username = "user", password = Secret("secret"), token = Secret("token"))
            )
        }
        validateNatsDestination("e", natsDestination(username = "user", password = Secret("secret")))
        validateNatsSource("o", natsSource().copy(token = Secret("token")))
    }
}
