package org.nxtspec.e2e

import com.rabbitmq.client.ConnectionFactory
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests to verify the E2E test infrastructure is working correctly.
 */
class E2EInfrastructureTest : E2ETestBase() {

    @Test
    fun `postgres container should be running and accessible`() {
        assertTrue(postgres.isRunning, "PostgreSQL container should be running")

        // Verify we can insert and read outbox messages
        val payload = JsonObject(mapOf("test" to JsonPrimitive("value")))
        val id = insertOutboxMessage(topic = "infra.test", payload = payload)

        val state = getOutboxMessageState(id)
        assertEquals("pending", state, "Initial state should be pending")
    }

    @Test
    fun `rabbitmq container should be running and accessible`() {
        assertTrue(rabbitMQ.isRunning, "RabbitMQ container should be running")

        // Verify we can connect
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        val connection = factory.newConnection()
        try {
            assertTrue(connection.isOpen, "Should be able to open RabbitMQ connection")
            val channel = connection.createChannel()
            try {
                // Declare a test queue
                channel.queueDeclare("e2e-infra-test", false, false, true, null)
            } finally {
                channel.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `mock http server should receive and track requests`() = runBlocking {
        val server = startMockHttpServer(
            responseCode = HttpStatusCode.OK,
            responseBody = """{"received": true}"""
        )

        // Make a request to the mock server
        val client = io.ktor.client.HttpClient()
        try {
            client.post("${server.baseUrl}/test/endpoint") {
                contentType(ContentType.Application.Json)
                setBody("""{"message": "hello"}""")
            }
        } finally {
            client.close()
        }

        // Verify request was tracked
        assertEquals(1, server.receivedRequests.size, "Should have received 1 request")
        assertEquals("/test/endpoint", server.receivedRequests[0].path)
        assertTrue(server.receivedRequests[0].body.contains("hello"))
    }

    @Test
    fun `mock http server should support configurable error responses`() = runBlocking {
        val server = startMockHttpServer(
            responseCode = HttpStatusCode.InternalServerError,
            responseBody = """{"error": "simulated failure"}"""
        )

        val client = io.ktor.client.HttpClient {
            expectSuccess = false
        }

        try {
            val response = client.post("${server.baseUrl}/failing/endpoint") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        } finally {
            client.close()
        }

        assertEquals(1, server.receivedRequests.size)
    }

    @Test
    fun `database cleanup should work between tests`() {
        // Insert a message
        val id = insertOutboxMessage(topic = "cleanup.test")
        assertNotNull(getOutboxMessageState(id))

        // Manually call cleanup (simulating what happens between tests)
        cleanupData()

        // Now inserting should work fresh
        val id2 = insertOutboxMessage(topic = "cleanup.test2")
        assertEquals("pending", getOutboxMessageState(id2))
    }

    @Test
    fun `inbox helpers should correctly store and query messages`() {
        // Insert via helper in base class
        insertInboxMessage(
            source = "test-source",
            idempotencyKey = "test-key-123",
            payload = JsonObject(mapOf("data" to JsonPrimitive("test")))
        )

        // Verify we can query it
        val message = getInboxMessage("test-source", "test-key-123")
        assertNotNull(message, "Should find inserted inbox message")
        assertEquals("test-source", message.source)
        assertEquals("test-key-123", message.idempotencyKey)
        assertEquals("pending", message.state)

        // Verify count
        assertEquals(1, countInboxMessages("test-source"))
        assertEquals(0, countInboxMessages("non-existent"))
    }

    @Test
    fun `E2ETestUtils should create valid config`() {
        val config = E2ETestUtils.createTestConfig(
            postgres = postgres,
            httpUrl = "http://localhost:8080",
            rabbitUrl = amqpUrl,
            routes = listOf(
                E2ETestUtils.httpRoute(topicPattern = "order.*"),
                E2ETestUtils.rabbitMQRoute(topicPattern = "event.*")
            ),
            sources = mapOf(
                "stripe" to E2ETestUtils.httpSource(path = "/stripe", idempotencyKeyPath = "$.id")
            )
        )

        // Verify database config
        assertEquals(postgres.jdbcUrl, config.database.url)
        assertEquals(postgres.username, config.database.username)

        // Verify destinations
        assertEquals(2, config.destinations.size)
        assertTrue(config.destinations.containsKey("http-destination"))
        assertTrue(config.destinations.containsKey("rabbitmq-destination"))

        // Verify routes
        assertEquals(2, config.routes.size)
        assertEquals("order.*", config.routes[0].topicPattern)
        assertEquals("event.*", config.routes[1].topicPattern)

        // Verify sources
        assertEquals(1, config.sources.size)
        assertTrue(config.sources.containsKey("stripe"))
    }

    @Test
    fun `RabbitMQ test consumer should receive messages`() = runBlocking {
        // Setup consumer
        val consumer = RabbitMQTestConsumer(amqpUrl, "test-consumer-queue")
        consumer.start()

        // Publish a message
        val publisher = RabbitMQTestPublisher(amqpUrl)
        publisher.publishToQueue(
            queueName = "test-consumer-queue",
            payload = """{"test": "data"}""",
            headers = mapOf("x-event-type" to "test.event"),
            messageId = "msg-123"
        )

        // Wait for message
        val received = consumer.waitForMessages(expectedCount = 1, timeoutMs = 3000)
        assertTrue(received, "Should receive message within timeout")
        assertEquals(1, consumer.receivedMessages.size)
        assertTrue(consumer.receivedMessages[0].body.contains("test"))
        assertEquals("msg-123", consumer.receivedMessages[0].messageId)

        consumer.stop()
    }
}
