package org.nxtspec.app

import org.nxtspec.ConfigLoader
import org.nxtspec.SourceConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers F-097. `rabbitConsumerConfig` converts a parsed `SourceConfig.RabbitMQ` into the
 * `RabbitConsumerConfig` that the consumer reads. A field present on `SourceConfig.RabbitMQ` but
 * never copied in `rabbitConsumerConfig` parses without error and is silently dropped, so a unit
 * test built on a hand-made `SourceConfig.RabbitMQ` proves nothing about the real path: it never
 * proves that Hoplite parses the YAML key into that field in the first place. This test starts
 * from YAML text, the way a deployment does, and follows it all the way to the consumer
 * configuration.
 *
 * Deleting the line `declareQueue = source.declareQueue` in `rabbitConsumerConfig` breaks no
 * other test in the suite. It must break this one.
 */
class SourceFromYamlTest {

    private fun loadRabbitMqSource(yaml: String): SourceConfig.RabbitMQ {
        val configFile = File.createTempFile("queuebox-source-from-yaml", ".yml")
        configFile.deleteOnExit()
        configFile.writeText(yaml)

        val config = ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to configFile.absolutePath) })
        return assertIs<SourceConfig.RabbitMQ>(config.sources.getValue("orders-queue"))
    }

    @Test
    fun `a RabbitMQ declareQueue true arrives on the consumer configuration`() {
        val source = loadRabbitMqSource(
            """
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret

            sources:
              orders-queue:
                type: rabbitmq
                queueName: incoming-orders
                connectionUrl: amqp://guest:guest@localhost:5672
                declareQueue: true
            """.trimIndent()
        )

        // The setting defaults to false, so a value of true here can arrive only through the
        // real Hoplite parse, not through a config object built by hand.
        assertTrue(source.declareQueue)

        val consumerConfig = rabbitConsumerConfig("orders-queue", source)
        assertEquals(true, consumerConfig.declareQueue)
    }
}
