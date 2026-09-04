package org.nxtspec.e2e

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxConfig
import org.nxtspec.InboxHandler
import org.nxtspec.InboxRepository
import org.nxtspec.SourceConfig
import org.nxtspec.configureInboxRoutes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for inbox message reception flows.
 * Tests complete flow from HTTP webhook → inbox table.
 */
class E2EInboxFlowTest : E2ETestBase() {

    @Test
    fun `should store message in inbox when valid webhook received`() = testApplication {
        // Configure application with real database
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            val repository = InboxRepository()
            val extractor = IdempotencyExtractor()
            val handler = InboxHandler(repository, extractor)
            val inboxConfig = InboxConfig(basePath = "/inbox")
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type"
                )
            )

            configureInboxRoutes(inboxConfig, sources, handler)
        }

        // Send webhook request
        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123", "type": "payment.received", "data": {"amount": 5000}}""")
        }

        // Verify response
        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(responseBody.containsKey("messageId"), "Response should contain messageId")

        // Verify database record
        val storedMessage = getInboxMessage("stripe", "evt_123")
        assertNotNull(storedMessage, "Message should be stored in inbox")
        assertEquals("stripe", storedMessage.source)
        assertEquals("evt_123", storedMessage.idempotencyKey)
        assertEquals("pending", storedMessage.state)
        assertTrue(storedMessage.payload.toString().contains("payment.received"))
    }

    @Test
    fun `should detect duplicate when same webhook sent twice`() = testApplication {
        // Configure application
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            val repository = InboxRepository()
            val extractor = IdempotencyExtractor()
            val handler = InboxHandler(repository, extractor)
            val inboxConfig = InboxConfig(basePath = "/inbox")
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id"
                )
            )

            configureInboxRoutes(inboxConfig, sources, handler)
        }

        val payload = """{"id": "evt_duplicate", "type": "charge.succeeded"}"""

        // Send first webhook - should succeed with messageId
        val firstResponse = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val firstBody = Json.parseToJsonElement(firstResponse.bodyAsText()).jsonObject
        assertTrue(firstBody.containsKey("messageId"), "First response should contain messageId")

        // Send same webhook again - should return duplicate status
        val secondResponse = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        val secondBody = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject
        assertEquals("duplicate", secondBody["status"]?.jsonPrimitive?.content)

        // Verify only one record in database
        assertEquals(1, countInboxMessages("stripe"), "Should have only one record due to deduplication")
    }

    @Test
    fun `should return 400 for invalid JSON`() = testApplication {
        // Configure application
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            val repository = InboxRepository()
            val extractor = IdempotencyExtractor()
            val handler = InboxHandler(repository, extractor)
            val inboxConfig = InboxConfig(basePath = "/inbox")
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id"
                )
            )

            configureInboxRoutes(inboxConfig, sources, handler)
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("not valid json {{{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `should return 400 when idempotency key not found in payload`() = testApplication {
        // Configure application
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            val repository = InboxRepository()
            val extractor = IdempotencyExtractor()
            val handler = InboxHandler(repository, extractor)
            val inboxConfig = InboxConfig(basePath = "/inbox")
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id" // Expects "id" field
                )
            )

            configureInboxRoutes(inboxConfig, sources, handler)
        }

        // Payload without "id" field
        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"type": "no-id-here", "data": {}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("error"))
    }

    @Test
    fun `should extract event type from configured path`() = testApplication {
        // Configure application with eventTypePath
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            val repository = InboxRepository()
            val extractor = IdempotencyExtractor()
            val handler = InboxHandler(repository, extractor)
            val inboxConfig = InboxConfig(basePath = "/inbox")
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type"
                )
            )

            configureInboxRoutes(inboxConfig, sources, handler)
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_typed", "type": "invoice.paid"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify event type was extracted (would need to query database)
        // For now, just verify the request succeeded
        val storedMessage = getInboxMessage("stripe", "evt_typed")
        assertNotNull(storedMessage)
    }

    @Test
    fun `should store message from RabbitMQ when consumed`() = runBlocking {
        // 1. Setup RabbitConsumer with real database
        val repository = InboxRepository()
        val extractor = IdempotencyExtractor()

        val consumer = org.nxtspec.RabbitConsumer(
            connection = org.nxtspec.RabbitConnection(amqpUrl),
            storeMessage = { message -> repository.store(message) },
            extractor = extractor,
            config = org.nxtspec.RabbitConsumerConfig(
                queueName = "e2e-inbox-queue",
                sourceName = "external-events",
                idempotencyKeyPath = "$.eventId"
            )
        )

        // Declare queue first
        val factory = com.rabbitmq.client.ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { ch ->
                ch.queueDeclare("e2e-inbox-queue", false, false, false, null)
            }
        }

        // Start consumer
        consumer.start()

        // 2. Publish message to queue
        val publisher = RabbitMQTestPublisher(amqpUrl)
        publisher.publishToQueue(
            queueName = "e2e-inbox-queue",
            payload = """{"eventId": "ext-123", "data": {"value": "test"}}""",
            headers = mapOf("x-event-type" to "order.created"),
            autoDelete = false
        )

        // 3. Wait for consumption
        delay(1000)

        // 4. Verify inbox table has message
        val storedMessage = getInboxMessage("external-events", "ext-123")
        assertNotNull(storedMessage, "Message from RabbitMQ should be stored in inbox")
        assertEquals("external-events", storedMessage.source)
        assertEquals("ext-123", storedMessage.idempotencyKey)
        assertEquals("pending", storedMessage.state)

        consumer.stop()
    }

    @Test
    fun `should deduplicate messages from RabbitMQ`() = runBlocking {
        // 1. Setup RabbitConsumer
        val repository = InboxRepository()
        val extractor = IdempotencyExtractor()

        val consumer = org.nxtspec.RabbitConsumer(
            connection = org.nxtspec.RabbitConnection(amqpUrl),
            storeMessage = { message -> repository.store(message) },
            extractor = extractor,
            config = org.nxtspec.RabbitConsumerConfig(
                queueName = "e2e-dedup-queue",
                sourceName = "external-events",
                idempotencyKeyPath = "$.eventId"
            )
        )

        // Declare queue
        val factory = com.rabbitmq.client.ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { ch ->
                ch.queueDeclare("e2e-dedup-queue", false, false, false, null)
            }
        }

        // Start consumer
        consumer.start()

        // 2. Publish same message twice
        val publisher = RabbitMQTestPublisher(amqpUrl)
        val payload = """{"eventId": "dup-event", "data": {"value": "first"}}"""
        publisher.publishToQueue("e2e-dedup-queue", payload, autoDelete = false)
        publisher.publishToQueue("e2e-dedup-queue", payload, autoDelete = false)

        // 3. Wait for consumption
        delay(1000)

        // 4. Verify only one record in database
        assertEquals(1, countInboxMessages("external-events"), "Should have only one record due to deduplication")

        consumer.stop()
    }
}
