package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Tests for the strict configuration check. See issue #65 and `docs/specs/strict-config.md`.
 *
 * An unknown key, an unknown `QUEUEBOX_*` variable and a key that does not fit the `type` of its
 * entry each stop the start. One error lists every offending path.
 */
class StrictConfigTest {

    private val database = """
        database:
          url: jdbc:postgresql://localhost:5432/queuebox
          username: queuebox
          password: secret
    """.trimIndent()

    private fun fileEnv(yaml: String, extra: Map<String, String> = emptyMap()): () -> Map<String, String> {
        val file = File.createTempFile("queuebox-strict", ".yml")
        file.deleteOnExit()
        file.writeText(database + "\n" + yaml.trimIndent())
        return { mapOf(ConfigLoader.CONFIG_FILE_ENV to file.absolutePath) + extra }
    }

    private fun failure(yaml: String, extra: Map<String, String> = emptyMap()): String =
        assertFailsWith<ConfigException> { ConfigLoader.load(env = fileEnv(yaml, extra)) }.message.orEmpty()

    @Test
    fun `a misspelled key stops the start with a suggestion`() {
        val message = failure(
            """
            outbox:
              batchSzie: 10
            """
        )

        assertContains(message, "outbox.batchSzie: unknown key; did you mean batchSize?")
    }

    @Test
    fun `a key that the type of a destination does not take stops the start`() {
        val message = failure(
            """
            destinations:
              d:
                type: rabbitmq
                baseUrl: https://x
            """
        )

        assertContains(message, "destinations.d.baseUrl: unknown key for type rabbitmq")
    }

    @Test
    fun `a destination without type stops the start`() {
        val message = failure(
            """
            destinations:
              d:
                baseUrl: https://x
            """
        )

        assertContains(message, "destinations.d: missing type")
    }

    @Test
    fun `an auth block without type stops the start`() {
        val message = failure(
            """
            destinations:
              d:
                type: http
                baseUrl: https://x
                auth:
                  username: u
                  password: p
            """
        )

        assertContains(message, "destinations.d.auth: missing type")
    }

    @Test
    fun `an unknown type stops the start with a suggestion`() {
        val message = failure(
            """
            destinations:
              d:
                type: rabbit
                url: amqp://localhost
                exchange: e
            """
        )

        assertContains(message, "destinations.d.type: unknown type rabbit; did you mean rabbitmq?")
    }

    @Test
    fun `a source without type starts as an HTTP source`() {
        val config = ConfigLoader.load(
            env = fileEnv(
                """
                sources:
                  stripe:
                    path: /stripe
                    idempotencyKeyPath: $.id
                    eventTypePath: $.type
                """
            )
        )

        val source = assertIs<SourceConfig.Http>(config.sources["stripe"])
        assertEquals("/stripe", source.path)
    }

    @Test
    fun `type selects the kind, so a source with RabbitMQ keys and no type is an HTTP source`() {
        val message = failure(
            """
            sources:
              orders:
                queueName: orders
                connectionUrl: amqp://localhost
            """
        )

        assertContains(message, "sources.orders.queueName: unknown key for type http")
        assertContains(message, "sources.orders.connectionUrl: unknown key for type http")
    }

    @Test
    fun `a misspelled variable stops the start and names the variable`() {
        val message = failure("", mapOf("QUEUEBOX_OUTBOX_BATCHSZIE" to "10"))

        assertContains(message, "QUEUEBOX_OUTBOX_BATCHSZIE: unknown variable; did you mean QUEUEBOX_OUTBOX_BATCHSIZE?")
    }

    @Test
    fun `Kubernetes service link variables do not stop the start`() {
        val config = ConfigLoader.load(
            env = fileEnv(
                "",
                mapOf(
                    "QUEUEBOX_DB_SERVICE_HOST" to "10.0.0.1",
                    "QUEUEBOX_DB_SERVICE_PORT" to "5432",
                    "QUEUEBOX_DB_SERVICE_PORT_POSTGRES" to "5432",
                    "QUEUEBOX_PORT" to "tcp://10.0.0.2:8080",
                    "QUEUEBOX_PORT_8080_TCP" to "tcp://10.0.0.2:8080",
                    "QUEUEBOX_PORT_8080_TCP_ADDR" to "10.0.0.2",
                    "QUEUEBOX_SERVER_PORT" to "tcp://10.0.0.3:8080"
                )
            )
        )

        assertEquals(8080, config.server.httpPort)
    }

    @Test
    fun `a service link form that binds a setting still binds it`() {
        val config = ConfigLoader.load(
            env = fileEnv("", mapOf("QUEUEBOX_OUTBOX_CAPTURE_CONNECTION_PORT" to "1433"))
        )

        assertEquals(1433, config.outbox.capture.connection.port)
    }

    @Test
    fun `three mistakes produce one error that lists all three`() {
        val message = failure(
            """
            outbox:
              batchSzie: 10
            destinations:
              d:
                baseUrl: https://x
            """,
            mapOf("QUEUEBOX_SERVER_HTTPPROT" to "9090")
        )

        assertContains(message, "outbox.batchSzie: unknown key")
        assertContains(message, "destinations.d: missing type")
        assertContains(message, "QUEUEBOX_SERVER_HTTPPROT: unknown variable; did you mean QUEUEBOX_SERVER_HTTPPORT?")
        assertContains(message, "3 errors")
    }

    @Test
    fun `an unknown key inside a list element names the index`() {
        val message = failure(
            """
            destinations:
              d:
                type: http
                baseUrl: https://x
            routes:
              - topicPattern: "a.*"
                destination: d
                destinaton: d
            """
        )

        assertContains(message, "routes[0].destinaton: unknown key; did you mean destination?")
    }

    @Test
    fun `a user map key is not checked against the schema`() {
        val config = ConfigLoader.load(
            env = fileEnv(
                """
                destinations:
                  my_webhook:
                    type: http
                    baseUrl: https://x
                    headers:
                      X-Custom-Header: v
                """
            )
        )

        val destination = assertIs<DestinationConfig.Http>(config.destinations["my_webhook"])
        assertEquals("v", destination.headers["X-Custom-Header"])
    }
}
