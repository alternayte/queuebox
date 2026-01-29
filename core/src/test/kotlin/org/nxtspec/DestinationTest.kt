package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DestinationTest {

    private val json = Json {
        prettyPrint = true
        classDiscriminator = "type"
    }

    @Test
    fun `Http destination should store all fields correctly`() {
        val http = Destination.Http(
            name = "webhook-service",
            baseUrl = "https://api.example.com",
            path = "/webhooks/receive",
            timeoutMs = 5000,
            headers = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json")
        )

        assertEquals("webhook-service", http.name)
        assertEquals("https://api.example.com", http.baseUrl)
        assertEquals("/webhooks/receive", http.path)
        assertEquals(5000, http.timeoutMs)
        assertEquals(2, http.headers.size)
        assertEquals("Bearer token", http.headers["Authorization"])
        assertEquals("application/json", http.headers["Content-Type"])
    }

    @Test
    fun `Http destination should have correct defaults`() {
        val http = Destination.Http(
            name = "simple",
            baseUrl = "https://api.example.com"
        )

        assertEquals("/", http.path)
        assertEquals(30000, http.timeoutMs)
        assertTrue(http.headers.isEmpty())
    }

    @Test
    fun `RabbitMQ destination should store all fields correctly`() {
        val rabbitmq = Destination.RabbitMQ(
            name = "queue-service",
            url = "amqp://localhost:5672",
            exchange = "events",
            exchangeType = "direct",
            routingKeyTemplate = "orders.{{ action }}",
            headers = mapOf("X-Source" to "queuebox", "X-Version" to "1.0")
        )

        assertEquals("queue-service", rabbitmq.name)
        assertEquals("amqp://localhost:5672", rabbitmq.url)
        assertEquals("events", rabbitmq.exchange)
        assertEquals("direct", rabbitmq.exchangeType)
        assertEquals("orders.{{ action }}", rabbitmq.routingKeyTemplate)
        assertEquals(2, rabbitmq.headers.size)
        assertEquals("queuebox", rabbitmq.headers["X-Source"])
        assertEquals("1.0", rabbitmq.headers["X-Version"])
    }

    @Test
    fun `RabbitMQ destination should have correct defaults`() {
        val rabbitmq = Destination.RabbitMQ(
            name = "simple",
            url = "amqp://localhost:5672",
            exchange = "default"
        )

        assertEquals("topic", rabbitmq.exchangeType)
        assertEquals("{{ topic }}", rabbitmq.routingKeyTemplate)
        assertTrue(rabbitmq.headers.isEmpty())
    }

    @Test
    fun `should support exhaustive when expression pattern matching`() {
        val destinations: List<Destination> = listOf(
            Destination.Http(name = "http", baseUrl = "https://example.com"),
            Destination.RabbitMQ(name = "rabbitmq", url = "amqp://localhost", exchange = "events")
        )

        val results = destinations.map { destination ->
            when (destination) {
                is Destination.Http -> "HTTP: ${destination.baseUrl}"
                is Destination.RabbitMQ -> "RabbitMQ: ${destination.exchange}"
            }
        }

        assertEquals("HTTP: https://example.com", results[0])
        assertEquals("RabbitMQ: events", results[1])
    }

    @Test
    fun `Http destination should serialize with correct type discriminator`() {
        val http = Destination.Http(
            name = "test",
            baseUrl = "https://example.com"
        )

        val serialized = json.encodeToString<Destination>(http)

        assertTrue(serialized.contains("\"type\""))
        assertTrue(serialized.contains("\"http\""))
    }

    @Test
    fun `RabbitMQ destination should serialize with correct type discriminator`() {
        val rabbitmq = Destination.RabbitMQ(
            name = "test",
            url = "amqp://localhost",
            exchange = "events"
        )

        val serialized = json.encodeToString<Destination>(rabbitmq)

        assertTrue(serialized.contains("\"type\""))
        assertTrue(serialized.contains("\"rabbitmq\""))
    }

    @Test
    fun `should deserialize Http destination from JSON`() {
        val jsonString = """
            {
                "type": "http",
                "name": "deserialized-http",
                "baseUrl": "https://test.example.com",
                "path": "/api",
                "timeoutMs": 10000,
                "headers": {"X-Custom": "value"}
            }
        """.trimIndent()

        val destination = json.decodeFromString<Destination>(jsonString)

        assertIs<Destination.Http>(destination)
        assertEquals("deserialized-http", destination.name)
        assertEquals("https://test.example.com", destination.baseUrl)
        assertEquals("/api", destination.path)
        assertEquals(10000, destination.timeoutMs)
        assertEquals("value", destination.headers["X-Custom"])
    }

    @Test
    fun `should deserialize RabbitMQ destination from JSON`() {
        val jsonString = """
            {
                "type": "rabbitmq",
                "name": "deserialized-rabbitmq",
                "url": "amqp://test-host:5672",
                "exchange": "test-exchange",
                "exchangeType": "fanout",
                "routingKeyTemplate": "test.key"
            }
        """.trimIndent()

        val destination = json.decodeFromString<Destination>(jsonString)

        assertIs<Destination.RabbitMQ>(destination)
        assertEquals("deserialized-rabbitmq", destination.name)
        assertEquals("amqp://test-host:5672", destination.url)
        assertEquals("test-exchange", destination.exchange)
        assertEquals("fanout", destination.exchangeType)
        assertEquals("test.key", destination.routingKeyTemplate)
    }

    @Test
    fun `should roundtrip serialize and deserialize Http destination`() {
        val original = Destination.Http(
            name = "roundtrip-http",
            baseUrl = "https://api.example.com",
            path = "/webhook",
            timeoutMs = 15000,
            headers = mapOf("Auth" to "secret")
        )

        val serialized = json.encodeToString<Destination>(original)
        val deserialized = json.decodeFromString<Destination>(serialized)

        assertIs<Destination.Http>(deserialized)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.baseUrl, deserialized.baseUrl)
        assertEquals(original.path, deserialized.path)
        assertEquals(original.timeoutMs, deserialized.timeoutMs)
        assertEquals(original.headers, deserialized.headers)
    }

    @Test
    fun `should roundtrip serialize and deserialize RabbitMQ destination`() {
        val original = Destination.RabbitMQ(
            name = "roundtrip-rabbitmq",
            url = "amqp://rabbit.example.com:5672",
            exchange = "my-exchange",
            exchangeType = "headers",
            routingKeyTemplate = "custom.{{ id }}",
            headers = mapOf("X-Static" to "value")
        )

        val serialized = json.encodeToString<Destination>(original)
        val deserialized = json.decodeFromString<Destination>(serialized)

        assertIs<Destination.RabbitMQ>(deserialized)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.url, deserialized.url)
        assertEquals(original.exchange, deserialized.exchange)
        assertEquals(original.exchangeType, deserialized.exchangeType)
        assertEquals(original.routingKeyTemplate, deserialized.routingKeyTemplate)
        assertEquals(original.headers, deserialized.headers)
    }

    @Test
    fun `should deserialize RabbitMQ destination with headers from JSON`() {
        val jsonString = """
            {
                "type": "rabbitmq",
                "name": "rabbitmq-with-headers",
                "url": "amqp://test-host:5672",
                "exchange": "test-exchange",
                "headers": {"X-Source": "queuebox", "X-Priority": "high"}
            }
        """.trimIndent()

        val destination = json.decodeFromString<Destination>(jsonString)

        assertIs<Destination.RabbitMQ>(destination)
        assertEquals("rabbitmq-with-headers", destination.name)
        assertEquals(2, destination.headers.size)
        assertEquals("queuebox", destination.headers["X-Source"])
        assertEquals("high", destination.headers["X-Priority"])
    }
}
