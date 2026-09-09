package org.nxtspec.app

import org.nxtspec.ConfigLoader
import org.nxtspec.Destination
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers F-091 and F-092. `toDestination` converts a parsed `DestinationConfig` into the domain
 * `Destination` that production reads. A field present on `DestinationConfig` but never copied in
 * `toDestination` parses without error and is silently dropped, so a unit test of `toDestination`
 * with a hand-built `DestinationConfig` proves nothing about the real path: it never proves that
 * Hoplite parses the YAML key into that field in the first place. This test starts from YAML text,
 * the way a deployment does, and follows it all the way to the domain object.
 *
 * Deleting one field assignment in `toDestination`, for example `topicFrom =
 * destConfig.topicFrom`, breaks no other test in the suite. It must break this one.
 */
class DestinationFromYamlTest {

    private fun loadDestinations(yaml: String): Map<String, Destination> {
        val configFile = File.createTempFile("queuebox-destination-from-yaml", ".yml")
        configFile.deleteOnExit()
        configFile.writeText(yaml)

        val config = ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to configFile.absolutePath) })
        return config.destinations.mapValues { (name, destConfig) -> toDestination(name, destConfig) }
    }

    @Test
    fun `a RabbitMQ exchangeFrom and routingKeyTemplate arrive on the domain destination`() {
        val destinations = loadDestinations(
            """
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret

            destinations:
              events:
                type: rabbitmq
                url: amqp://guest:guest@localhost:5672
                exchange: fallback-exchange
                exchangeFrom: aggregate_type
                routingKeyTemplate: "{{ aggregateType }}"
            """.trimIndent()
        )

        val destination = assertIs<Destination.RabbitMQ>(destinations.getValue("events"))
        assertEquals("aggregate_type", destination.exchangeFrom)
        assertEquals("{{ aggregateType }}", destination.routingKeyTemplate)
    }

    @Test
    fun `a Kafka topicFrom arrives on the domain destination`() {
        val destinations = loadDestinations(
            """
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret

            destinations:
              events:
                type: kafka
                bootstrapServers: localhost:9092
                topic: fallback-topic
                topicFrom: topic
            """.trimIndent()
        )

        val destination = assertIs<Destination.Kafka>(destinations.getValue("events"))
        assertEquals("topic", destination.topicFrom)
    }

    @Test
    fun `a NATS subjectFrom arrives on the domain destination`() {
        val destinations = loadDestinations(
            """
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret

            destinations:
              events:
                type: nats
                servers: nats://localhost:4222
                subject: fallback-subject
                subjectFrom: key
            """.trimIndent()
        )

        val destination = assertIs<Destination.Nats>(destinations.getValue("events"))
        assertEquals("key", destination.subjectFrom)
    }
}
