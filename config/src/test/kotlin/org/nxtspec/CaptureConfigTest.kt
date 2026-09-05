package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureConfigTest {

    @Test
    fun `capture and consumption settings load from yaml`() {
        val config = ConfigLoader.load("capture-config.yml")
        val capture = config.outbox.capture

        assertEquals("postgres-logical", capture.mode)
        assertTrue(capture.enabled)
        assertEquals("orders_capture", capture.identity)
        assertEquals("/var/lib/queuebox/capture", capture.stateDirectory)
        assertEquals("events", capture.schema)
        assertEquals("orders_publication", capture.publication)
        assertEquals("orders_slot", capture.slot)
        assertEquals(2000, capture.reconciliationIntervalMs)
        assertEquals("replica.example.com", capture.connection.hostname)
        assertEquals(5433, capture.connection.port)
        assertEquals("queuebox_replica", capture.connection.database)
        assertEquals("capture_user", capture.connection.username)
        assertEquals("capture_secret", capture.connection.password?.reveal())

        assertEquals("pull", config.sources["pull-source"]?.consumption)
        assertEquals("push", config.sources["push-source"]?.consumption)
    }

    @Test
    fun `capture stays off by default and delivery keeps polling`() {
        val config = ConfigLoader.load("test-config.yml")

        assertEquals("polling", config.outbox.capture.mode)
        assertEquals(false, config.outbox.capture.enabled)
        assertEquals(1000, config.outbox.capture.reconciliationIntervalMs)
    }

    private fun validated(capture: CaptureConfig, databaseType: String = "postgresql") {
        val url =
            if (databaseType == "postgresql") {
                "jdbc:postgresql://localhost:5432/queuebox"
            } else {
                "jdbc:sqlserver://localhost:1433;databaseName=queuebox"
            }
        ConfigValidator.validate(
            QueueBoxConfig(
                server = ServerConfig(httpPort = 8080),
                database = DatabaseConfig(
                    type = databaseType,
                    url = url,
                    username = "user",
                    password = Secret("secret")
                ),
                outbox = OutboxConfig(capture = capture),
                inbox = InboxConfig(basePath = "/inbox"),
                destinations = mapOf("webhook" to DestinationConfig.Http(baseUrl = "https://example.com")),
                routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook")),
                sources = emptyMap()
            )
        )
    }

    private fun enabled(): CaptureConfig =
        CaptureConfig(mode = "postgres-logical", enabled = true, stateDirectory = "/var/lib/queuebox")

    @Test
    fun `an enabled capture accepts complete settings`() {
        validated(enabled())
    }

    @Test
    fun `capture rejects a mode that the database cannot serve`() {
        assertFailsWith<IllegalArgumentException> { validated(enabled(), databaseType = "sqlserver") }
        assertFailsWith<IllegalArgumentException> {
            validated(
                CaptureConfig(mode = "sqlserver-cdc", enabled = true, stateDirectory = "/var/lib/queuebox")
            )
        }
        assertFailsWith<IllegalArgumentException> { validated(CaptureConfig(mode = "kafka")) }
    }

    @Test
    fun `capture rejects incomplete or unsafe settings`() {
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(stateDirectory = "")) }
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(identity = "Bad Identity")) }
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(slot = "bad-slot")) }
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(publication = "bad publication")) }
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(schema = "bad schema")) }
        assertFailsWith<IllegalArgumentException> {
            validated(enabled().copy(connection = CaptureConnection(port = 0)))
        }
        assertFailsWith<IllegalArgumentException> { validated(enabled().copy(reconciliationIntervalMs = 0)) }
        assertFailsWith<IllegalArgumentException> {
            validated(CaptureConfig(mode = "polling", enabled = true, stateDirectory = "/var/lib/queuebox"))
        }
    }
}
