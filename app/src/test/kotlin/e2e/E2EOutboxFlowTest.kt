package org.nxtspec.e2e

import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.http.HttpPublisher
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E tests for outbox message delivery flows.
 * Tests complete flow from outbox table → poller → destination.
 */
class E2EOutboxFlowTest : E2ETestBase() {

    private var poller: OutboxPoller? = null

    @AfterEach
    fun shutdownPoller() {
        runBlocking {
            poller?.shutdown()
        }
        poller = null
    }

    @Test
    fun `should deliver message to HTTP endpoint when outbox message polled`() = runBlocking {
        // 1. Start mock HTTP server
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.OK,
            responseBody = """{"accepted": true}"""
        )

        // 2. Configure router with HTTP destination
        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "test-http"
            )
        )
        val router = MessageRouter(
            routes = routes,
            destinations = mapOf("test-http" to httpDestination)
        )

        // 3. Create repository and poller
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        val publisher = HttpPublisher()
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        // 4. Insert message into outbox
        val payload = JsonObject(mapOf(
            "orderId" to JsonPrimitive("order-123"),
            "amount" to JsonPrimitive(99.99),
            "currency" to JsonPrimitive("USD")
        ))
        val messageId = insertOutboxMessage(
            topic = "order.created",
            payload = payload
        )

        // 5. Start poller
        poller?.start()

        // 6. Wait for delivery
        var delivered = false
        repeat(50) { // Max 2.5 seconds
            delay(50)
            if (mockServer.receivedRequests.isNotEmpty()) {
                delivered = true
                return@repeat
            }
        }

        // 7. Verify HTTP endpoint received message
        assertTrue(delivered, "Message should be delivered to HTTP endpoint")
        assertEquals(1, mockServer.receivedRequests.size)

        val receivedRequest = mockServer.receivedRequests[0]
        assertTrue(receivedRequest.body.contains("order-123"))
        assertEquals("/webhook", receivedRequest.path)

        // Verify standard headers
        assertTrue(receivedRequest.headers.containsKey("X-Message-Id"))
        assertEquals("order.created", receivedRequest.headers["X-Topic"])
        assertEquals("0", receivedRequest.headers["X-Attempt"])

        // 8. Verify outbox message state
        delay(100) // Allow state update to complete
        val state = getOutboxMessageState(messageId)
        assertEquals("sent", state, "Message state should be 'sent'")

        publisher.close()
    }

    @Test
    fun `should deliver message to RabbitMQ when outbox message polled`() = runBlocking {
        // 1. Configure router with RabbitMQ destination
        // Note: routingKeyTemplate is on Destination.RabbitMQ, not RouteConfig
        val rabbitDestination = Destination.RabbitMQ(
            name = "test-rabbit",
            url = amqpUrl,
            exchange = "e2e-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "events.**",  // Match events.* and events.*.*
                destination = "test-rabbit"
            )
        )
        val router = MessageRouter(
            routes = routes,
            destinations = mapOf("test-rabbit" to rabbitDestination)
        )

        // 2. Setup RabbitMQ consumer - declare exchange as durable to match publisher
        val consumer = RabbitMQTestConsumer(amqpUrl, "e2e-outbox-queue")
        // First declare the exchange as durable (must match publisher)
        val factory = com.rabbitmq.client.ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { ch ->
                ch.exchangeDeclare("e2e-exchange", "topic", true)
                ch.queueDeclare("e2e-outbox-queue", false, false, true, null)
                ch.queueBind("e2e-outbox-queue", "e2e-exchange", "#")
            }
        }
        consumer.start() // Start consuming without exchange binding (already bound)

        // 3. Create repository and poller with RabbitMQ publisher
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        val publisher = org.nxtspec.RabbitPublisher()
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        // 4. Insert message into outbox
        val payload = JsonObject(mapOf(
            "userId" to JsonPrimitive("user-456"),
            "action" to JsonPrimitive("signup")
        ))
        val messageId = insertOutboxMessage(
            topic = "events.user.signup",
            payload = payload
        )

        // 5. Start poller
        poller?.start()

        // 6. Wait for RabbitMQ to receive message
        val received = consumer.waitForMessages(expectedCount = 1, timeoutMs = 5000)
        assertTrue(received, "Message should be delivered to RabbitMQ")
        assertEquals(1, consumer.receivedMessages.size)

        val receivedMessage = consumer.receivedMessages[0]
        assertTrue(receivedMessage.body.contains("user-456"))
        assertEquals("events.user.signup", receivedMessage.routingKey)

        // 7. Verify outbox message state
        delay(100) // Allow state update to complete
        val state = getOutboxMessageState(messageId)
        assertEquals("sent", state, "Message state should be 'sent'")

        consumer.stop()
        publisher.close()
    }

    @Test
    fun `should mark message dead when no route matches`() = runBlocking {
        // 1. Configure router with specific pattern that won't match
        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = "http://localhost:8888",
            path = "/webhook"
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "specific.topic.only",
                destination = "test-http"
            )
        )
        val router = MessageRouter(
            routes = routes,
            destinations = mapOf("test-http" to httpDestination)
        )

        // 2. Create poller
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        val publisher = HttpPublisher()
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        // 3. Insert message with non-matching topic
        val messageId = insertOutboxMessage(
            topic = "unmatched.topic",
            payload = JsonObject(emptyMap())
        )

        // 4. Start poller
        poller?.start()

        // 5. Wait for processing
        delay(500)

        // 6. Verify message is marked as dead
        val state = getOutboxMessageState(messageId)
        assertEquals("dead", state, "Message with no matching route should be marked 'dead'")

        publisher.close()
    }

    @Test
    fun `should retry and mark dead when HTTP delivery consistently fails`() = runBlocking {
        // 1. Start mock HTTP server that always returns 500
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.InternalServerError,
            responseBody = """{"error": "simulated failure"}"""
        )

        // 2. Configure router with HTTP destination
        val httpDestination = Destination.Http(
            name = "failing-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 2000
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = "fail.*", destination = "failing-http")),
            destinations = mapOf("failing-http" to httpDestination)
        )

        // 3. Create poller with fast retry for test
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 50,  // Very short for testing
            maxAttempts = 2
        )
        val publisher = HttpPublisher()
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        // 4. Insert message with maxAttempts=2
        val messageId = insertOutboxMessage(
            topic = "fail.test",
            payload = JsonObject(mapOf("test" to JsonPrimitive("retry-test"))),
            maxAttempts = 2
        )

        // 5. Start poller
        poller?.start()

        // 6. Wait for initial delivery attempt
        var attempts = 0
        repeat(30) {
            delay(100)
            attempts = mockServer.receivedRequests.size
            if (attempts >= 1) return@repeat
        }
        assertTrue(attempts >= 1, "Should have at least one delivery attempt")

        // 7. Wait for retry and eventual dead state
        // The message should go: pending -> processing -> (fail) -> pending (retry scheduled)
        // -> processing -> (fail) -> dead
        repeat(50) { // Max 5 seconds
            delay(100)
            val (state, attempt) = getOutboxMessageStateAndAttempt(messageId)
            if (state == "dead") {
                return@repeat
            }
        }

        // 8. Verify final state
        val (finalState, finalAttempt) = getOutboxMessageStateAndAttempt(messageId)
        assertEquals("dead", finalState, "Message should be marked 'dead' after exhausting retries")
        assertEquals(2, finalAttempt, "Should have attempted twice (0 + 1 retry)")

        // 9. Verify mock server received multiple requests
        assertTrue(mockServer.receivedRequests.size >= 2, "Should have made at least 2 delivery attempts")

        publisher.close()
    }

    @Test
    fun `should process multiple messages in batch`() = runBlocking {
        // 1. Start mock HTTP server
        val mockServer = startMockHttpServer()

        // 2. Configure router
        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook"
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = "batch.*", destination = "test-http")),
            destinations = mapOf("test-http" to httpDestination)
        )

        // 3. Create poller
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        val publisher = HttpPublisher()

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = RetryStrategy(config)
        )

        // 4. Insert multiple messages
        val messageIds = (1..5).map { i ->
            insertOutboxMessage(
                topic = "batch.test",
                payload = JsonObject(mapOf("index" to JsonPrimitive(i)))
            )
        }

        // 5. Start poller
        poller?.start()

        // 6. Wait for all messages to be delivered
        var allDelivered = false
        repeat(100) {
            delay(50)
            if (mockServer.receivedRequests.size >= 5) {
                allDelivered = true
                return@repeat
            }
        }

        assertTrue(allDelivered, "All 5 messages should be delivered")
        assertEquals(5, mockServer.receivedRequests.size)

        // 7. Verify all messages are marked as sent
        delay(100)
        messageIds.forEach { id ->
            assertEquals("sent", getOutboxMessageState(id), "Message $id should be 'sent'")
        }

        publisher.close()
    }
}
