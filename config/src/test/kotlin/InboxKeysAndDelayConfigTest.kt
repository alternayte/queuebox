package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Issues #83, #84 and #85. See `docs/specs/inbox-keys-and-delay.md`. */
class InboxKeysAndDelayConfigTest {

    private val database = """
        |database:
        |  url: jdbc:postgresql://db:5432/app
        |  username: app
        |  password: secret
    """.trimMargin()

    private fun load(sourceYaml: String): QueueBoxConfig {
        val file = File.createTempFile("queuebox-inbox-keys", ".yml").apply {
            deleteOnExit()
            writeText("$database\nsources:\n  orders:\n$sourceYaml\n")
        }
        return ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) })
    }

    private fun loadEnv(vararg source: Pair<String, String>): QueueBoxConfig = ConfigLoader.load(
        env = {
            mapOf(
                "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/app",
                "QUEUEBOX_DATABASE_USERNAME" to "app",
                "QUEUEBOX_DATABASE_PASSWORD" to "secret"
            ) + source
        }
    )

    private val rabbit = """
        |    type: rabbitmq
        |    queueName: orders
        |    connectionUrl: amqp://broker
    """.trimMargin()

    @Test
    fun `the expression keys, a path list and the delay load from YAML and from variables to the same source`() {
        // An expression that holds `: ` or starts with a quote needs YAML quotes.
        val fromYaml = load(
            rabbit + "\n" + """
            |    idempotencyKeyExpression: id & "-" & version
            |    eventTypeExpression: '${'$'}lookup({"a.v1": "A"}, type)'
            |    aggregateIdExpression: ${'$'}split(subject, "/")[2]
            |    aggregateIdPath:
            |      - ${'$'}.orderId
            |      - ${'$'}.OrderId
            |    initialDelay: 30s
            """.trimMargin()
        )
        val fromEnv = loadEnv(
            "QUEUEBOX_SOURCES_ORDERS_TYPE" to "rabbitmq",
            "QUEUEBOX_SOURCES_ORDERS_QUEUENAME" to "orders",
            "QUEUEBOX_SOURCES_ORDERS_CONNECTIONURL" to "amqp://broker",
            "QUEUEBOX_SOURCES_ORDERS_IDEMPOTENCYKEYEXPRESSION" to "id & \"-\" & version",
            "QUEUEBOX_SOURCES_ORDERS_EVENTTYPEEXPRESSION" to "\$lookup({\"a.v1\": \"A\"}, type)",
            "QUEUEBOX_SOURCES_ORDERS_AGGREGATEIDEXPRESSION" to "\$split(subject, \"/\")[2]",
            "QUEUEBOX_SOURCES_ORDERS_AGGREGATEIDPATH_0" to "\$.orderId",
            "QUEUEBOX_SOURCES_ORDERS_AGGREGATEIDPATH_1" to "\$.OrderId",
            "QUEUEBOX_SOURCES_ORDERS_INITIALDELAY" to "30s"
        )

        val source = fromYaml.sources.getValue("orders")
        assertEquals(KeyPaths("\$.orderId", "\$.OrderId"), source.aggregateIdPath)
        assertEquals("\$split(subject, \"/\")[2]", source.aggregateIdExpression)
        assertEquals(30.seconds, source.initialDelayDuration())
        assertEquals(fromYaml.sources, fromEnv.sources)
    }

    @Test
    fun `a single path string keeps its meaning, with and without a comma`() {
        val fromYaml = load("$rabbit\n    aggregateIdPath: ${'$'}['a,b']")
        val fromEnv = loadEnv(
            "QUEUEBOX_SOURCES_ORDERS_TYPE" to "rabbitmq",
            "QUEUEBOX_SOURCES_ORDERS_QUEUENAME" to "orders",
            "QUEUEBOX_SOURCES_ORDERS_CONNECTIONURL" to "amqp://broker",
            "QUEUEBOX_SOURCES_ORDERS_AGGREGATEIDPATH" to "\$['a,b']"
        )

        val source = fromYaml.sources.getValue("orders")
        assertEquals(KeyPaths("\$['a,b']"), source.aggregateIdPath)
        assertEquals(KeyPaths("\$.id"), source.idempotencyKeyPath)
        assertEquals(Duration.ZERO, source.initialDelayDuration())
        assertEquals(fromYaml.sources, fromEnv.sources)
    }

    @Test
    fun `a list with an indefinite path and an empty list stop the start`() {
        val indefinite = assertFailsWith<IllegalArgumentException> {
            load("$rabbit\n    aggregateIdPath: [${'$'}.orderId, ${'$'}..OrderId]")
        }
        assertContains(indefinite.message!!, "sources.orders.aggregateIdPath")
        assertContains(indefinite.message!!, "indefinite")

        val empty = assertFailsWith<IllegalArgumentException> { load("$rabbit\n    aggregateIdPath: []") }
        assertContains(empty.message!!, "sources.orders.aggregateIdPath")
    }

    @Test
    fun `an initial delay without a unit and a negative one stop the start`() {
        listOf("30", "-5s").forEach { delay ->
            val error = assertFailsWith<IllegalArgumentException> { load("$rabbit\n    initialDelay: \"$delay\"") }
            assertContains(error.message!!, "sources.orders.initialDelay")
        }
    }

    @Test
    fun `an idempotency key expression alone is enough for an HTTP source`() {
        val config = load(
            """
            |    type: http
            |    path: /orders
            |    topic: "{{ eventType }}"
            |    idempotencyKeyExpression: id
            |    eventTypeExpression: type
            """.trimMargin()
        )
        assertEquals("id", config.sources.getValue("orders").idempotencyKeyExpression)
    }

    // Issue #91. See `docs/specs/publish-time-order.md`.

    @Test
    fun `the publish time keys load from YAML and from variables to the same source`() {
        val fromYaml = load(
            rabbit + "\n" + """
            |    scheduledAtHeader: timestamp_in_ms
            |    scheduledAtPath: ${'$'}.time
            |    initialDelay: 30s
            """.trimMargin()
        )
        val fromEnv = loadEnv(
            "QUEUEBOX_SOURCES_ORDERS_TYPE" to "rabbitmq",
            "QUEUEBOX_SOURCES_ORDERS_QUEUENAME" to "orders",
            "QUEUEBOX_SOURCES_ORDERS_CONNECTIONURL" to "amqp://broker",
            "QUEUEBOX_SOURCES_ORDERS_SCHEDULEDATHEADER" to "timestamp_in_ms",
            "QUEUEBOX_SOURCES_ORDERS_SCHEDULEDATPATH" to "\$.time",
            "QUEUEBOX_SOURCES_ORDERS_INITIALDELAY" to "30s"
        )

        val source = fromYaml.sources.getValue("orders")
        assertEquals("timestamp_in_ms", source.scheduledAtHeader)
        assertEquals(KeyPaths("\$.time"), source.scheduledAtPath)
        assertEquals(fromYaml.sources, fromEnv.sources)
    }

    @Test
    fun `a publish time key without a delay above zero stops the start`() {
        val missing = assertFailsWith<IllegalArgumentException> { load("$rabbit\n    scheduledAtPath: ${'$'}.time") }
        assertContains(missing.message!!, "sources.orders.initialDelay")
        assertContains(missing.message!!, "scheduledAtPath")

        val zero = assertFailsWith<IllegalArgumentException> {
            load("$rabbit\n    scheduledAtHeader: timestamp_in_ms\n    initialDelay: 0s")
        }
        assertContains(zero.message!!, "sources.orders.initialDelay")
    }
}
