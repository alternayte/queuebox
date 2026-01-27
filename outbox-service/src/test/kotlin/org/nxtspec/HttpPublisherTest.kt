package org.nxtspec

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.http.HttpPublishException
import org.nxtspec.http.HttpPublisher
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpPublisherTest {

    private fun createClientWithEngine(
        mockEngine: MockEngine,
        timeoutMs: Long = 30000
    ): (Destination.Http) -> HttpClient = { _ ->
        HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs / 2
            }
            expectSuccess = false
        }
    }

    private fun createTestMessage(
        id: UUID = UUID.randomUUID(),
        topic: String = "test.topic",
        key: String? = null,
        attempt: Int = 1
    ): OutboxMessage = OutboxMessage(
        id = id,
        topic = topic,
        key = key,
        payload = JsonObject(mapOf("data" to JsonPrimitive("test"))),
        attempt = attempt
    )

    private fun createTestDestination(
        name: String = "test-dest",
        baseUrl: String = "http://example.com",
        path: String = "/webhook",
        timeoutMs: Long = 30000,
        headers: Map<String, String> = emptyMap()
    ): Destination.Http = Destination.Http(
        name = name,
        baseUrl = baseUrl,
        path = path,
        timeoutMs = timeoutMs,
        headers = headers
    )

    @Test
    fun `should return success when endpoint returns 200`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status": "ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val message = createTestMessage()
        val destination = createTestDestination()

        val result = publisher.publish(message, destination)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `should include correct standard headers when publishing`() = runTest {
        var capturedHeaders: Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respond("", HttpStatusCode.OK)
        }

        val testId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val message = createTestMessage(
            id = testId,
            topic = "order.created",
            attempt = 2
        )

        publisher.publish(message, createTestDestination())

        assertEquals("550e8400-e29b-41d4-a716-446655440000", capturedHeaders!!["X-Message-Id"])
        assertEquals("order.created", capturedHeaders!!["X-Topic"])
        assertEquals("2", capturedHeaders!!["X-Attempt"])
    }

    @Test
    fun `should include message key header when key is present`() = runTest {
        var capturedHeaders: Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val message = createTestMessage(key = "user-123")

        publisher.publish(message, createTestDestination())

        assertEquals("user-123", capturedHeaders!!["X-Message-Key"])
    }

    @Test
    fun `should not include message key header when key is null`() = runTest {
        var capturedHeaders: Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val message = createTestMessage(key = null)

        publisher.publish(message, createTestDestination())

        assertTrue(capturedHeaders!!["X-Message-Key"] == null)
    }

    @Test
    fun `should include custom headers from destination`() = runTest {
        var capturedHeaders: Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val destination = createTestDestination(
            headers = mapOf(
                "X-Api-Key" to "secret-key",
                "X-Custom-Header" to "custom-value"
            )
        )

        publisher.publish(createTestMessage(), destination)

        assertEquals("secret-key", capturedHeaders!!["X-Api-Key"])
        assertEquals("custom-value", capturedHeaders!!["X-Custom-Header"])
    }

    @Test
    fun `should send request to correct url`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val destination = createTestDestination(
            baseUrl = "http://api.example.com",
            path = "/events/webhook"
        )

        publisher.publish(createTestMessage(), destination)

        assertEquals("http://api.example.com/events/webhook", capturedUrl)
    }

    @Test
    fun `should return failure when endpoint returns 5xx`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val result = publisher.publish(createTestMessage(), createTestDestination())

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpPublishException)
        assertEquals(500, (exception as HttpPublishException).statusCode)
    }

    @Test
    fun `should return failure when endpoint returns 503`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Service Unavailable",
                status = HttpStatusCode.ServiceUnavailable
            )
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val result = publisher.publish(createTestMessage(), createTestDestination())

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpPublishException)
        assertEquals(503, (exception as HttpPublishException).statusCode)
    }

    @Test
    fun `should return failure when endpoint returns 4xx`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"error": "bad request"}""",
                status = HttpStatusCode.BadRequest
            )
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val result = publisher.publish(createTestMessage(), createTestDestination())

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpPublishException)
        assertEquals(400, (exception as HttpPublishException).statusCode)
    }

    @Test
    fun `should include response body in exception on failure`() = runTest {
        val errorBody = """{"error": "validation failed", "code": "INVALID_PAYLOAD"}"""
        val mockEngine = MockEngine { _ ->
            respond(
                content = errorBody,
                status = HttpStatusCode.BadRequest
            )
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val result = publisher.publish(createTestMessage(), createTestDestination())

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as HttpPublishException
        assertEquals(errorBody, exception.body)
    }

    @Test
    fun `should return failure when request times out`() = runTest {
        val mockEngine = MockEngine { _ ->
            throw HttpRequestTimeoutException("http://example.com/webhook", 100)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine, timeoutMs = 100))
        val destination = createTestDestination(timeoutMs = 100)
        val result = publisher.publish(createTestMessage(), destination)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpPublishException)
        assertTrue(exception.message!!.contains("timeout", ignoreCase = true))
    }

    @Test
    fun `should return failure with IllegalArgumentException for non-HTTP destination`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        val rabbitDestination = Destination.RabbitMQ(
            name = "rabbitmq",
            url = "amqp://localhost",
            exchange = "events"
        )

        val result = publisher.publish(createTestMessage(), rabbitDestination)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `should set content type to application json`() = runTest {
        var capturedContentType: ContentType? = null
        val mockEngine = MockEngine { request ->
            capturedContentType = request.body.contentType
            respond("", HttpStatusCode.OK)
        }

        val publisher = HttpPublisher(createClientWithEngine(mockEngine))
        publisher.publish(createTestMessage(), createTestDestination())

        assertEquals(ContentType.Application.Json, capturedContentType)
    }
}
