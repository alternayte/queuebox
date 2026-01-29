package org.nxtspec

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxRoutesTest {

    private fun ApplicationTestBuilder.setupInboxRoutes(
        handler: InboxHandler,
        sources: Map<String, SourceConfig> = mapOf(
            "stripe" to SourceConfig.Http(path = "/stripe", idempotencyKeyPath = "$.id")
        ),
        config: InboxConfig = InboxConfig(basePath = "/inbox")
    ) {
        install(ContentNegotiation) { json() }
        routing {
            // Filter to HTTP sources and configure routes directly
            sources.filterValues { it is SourceConfig.Http }
                .forEach { (sourceName, sourceConfig) ->
                    val httpConfig = sourceConfig as SourceConfig.Http
                    post("${config.basePath}${httpConfig.path}") {
                        val payload = try {
                            call.receive<kotlinx.serialization.json.JsonElement>()
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
                            return@post
                        }

                        when (val result = handler.handle(sourceName, httpConfig, payload)) {
                            is InboxHandlerResult.Accepted ->
                                call.respond(HttpStatusCode.OK, mapOf("messageId" to result.messageId.toString()))

                            is InboxHandlerResult.Duplicate ->
                                call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))

                            is InboxHandlerResult.ExtractionFailed ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.reason))

                            is InboxHandlerResult.TransformFailed ->
                                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "Transform failed: ${result.reason}"))

                            is InboxHandlerResult.StorageFailed ->
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Storage failed"))
                        }
                    }
                }
        }
    }

    @Test
    fun `should return 200 when valid webhook received and stored`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        setupInboxRoutes(handler)

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123", "type": "payment.success"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("messageId"))
    }

    @Test
    fun `should return 200 when duplicate message received`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Duplicate

        setupInboxRoutes(handler)

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("duplicate"))
    }

    @Test
    fun `should return 400 when invalid JSON received`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        setupInboxRoutes(handler)

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("not valid json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid JSON"))

        // Handler should never be called for invalid JSON
        coVerify(exactly = 0) { mockRepository.store(any()) }
    }

    @Test
    fun `should return 400 when idempotency key extraction fails`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        // Configure a source that expects "$.orderId" but we'll send "$.id"
        val sources = mapOf(
            "orders" to SourceConfig.Http(path = "/orders", idempotencyKeyPath = "$.orderId")
        )

        setupInboxRoutes(handler, sources)

        val response = client.post("/inbox/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "123", "data": "test"}""")  // Missing orderId
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        // Repository should not be called when extraction fails
        coVerify(exactly = 0) { mockRepository.store(any()) }
    }

    @Test
    fun `should return 500 when storage fails`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Error("Database connection failed")

        setupInboxRoutes(handler)

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Storage failed"))
    }

    @Test
    fun `should return 404 when unknown source path requested`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        // Only configure stripe source
        setupInboxRoutes(handler)

        val response = client.post("/inbox/unknown") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "123"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `should store message with correct source name`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        setupInboxRoutes(handler)

        client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_456"}""")
        }

        coVerify { mockRepository.store(match { it.source == "stripe" }) }
    }

    @Test
    fun `should store message with correct idempotency key`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        setupInboxRoutes(handler)

        client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_789", "type": "charge.succeeded"}""")
        }

        coVerify { mockRepository.store(match { it.idempotencyKey == "evt_789" }) }
    }

    @Test
    fun `should extract event type when configured`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sources = mapOf(
            "stripe" to SourceConfig.Http(
                path = "/stripe",
                idempotencyKeyPath = "$.id",
                eventTypePath = "$.type"
            )
        )

        setupInboxRoutes(handler, sources)

        client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123", "type": "payment.completed"}""")
        }

        coVerify { mockRepository.store(match { it.eventType == "payment.completed" }) }
    }

    @Test
    fun `should handle multiple sources with different paths`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sources = mapOf(
            "stripe" to SourceConfig.Http(path = "/stripe", idempotencyKeyPath = "$.id"),
            "github" to SourceConfig.Http(path = "/github", idempotencyKeyPath = "$.delivery")
        )

        setupInboxRoutes(handler, sources)

        // Test Stripe source
        val stripeResponse = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_stripe_123"}""")
        }
        assertEquals(HttpStatusCode.OK, stripeResponse.status)

        // Test GitHub source
        val githubResponse = client.post("/inbox/github") {
            contentType(ContentType.Application.Json)
            setBody("""{"delivery": "gh_delivery_456"}""")
        }
        assertEquals(HttpStatusCode.OK, githubResponse.status)

        coVerify { mockRepository.store(match { it.source == "stripe" && it.idempotencyKey == "evt_stripe_123" }) }
        coVerify { mockRepository.store(match { it.source == "github" && it.idempotencyKey == "gh_delivery_456" }) }
    }

    @Test
    fun `should use custom base path from config`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val config = InboxConfig(basePath = "/webhooks")
        val sources = mapOf(
            "stripe" to SourceConfig.Http(path = "/stripe", idempotencyKeyPath = "$.id")
        )

        setupInboxRoutes(handler, sources, config)

        val response = client.post("/webhooks/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `should include messageId in response on success`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        setupInboxRoutes(handler)

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_123"}""")
        }

        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body)
        assertTrue(json.toString().contains("messageId"))
    }

    @Test
    fun `should preserve full payload in stored message`() = testApplication {
        val mockRepository = mockk<InboxRepository>()
        val extractor = IdempotencyExtractor()
        val handler = InboxHandler(mockRepository, extractor)

        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        setupInboxRoutes(handler)

        val fullPayload = """{"id": "evt_123", "data": {"customer": "cus_abc", "amount": 1000}}"""
        client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody(fullPayload)
        }

        coVerify {
            mockRepository.store(match {
                it.payload.toString().contains("cus_abc") &&
                it.payload.toString().contains("1000")
            })
        }
    }
}
