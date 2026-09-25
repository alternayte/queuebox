package org.nxtspec.transform

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.nxtspec.TransformConfig
import org.nxtspec.TransformErrorStrategy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class InboxTransformPipelineTest {

    private val engine = TransformEngine()
    private val pipeline = InboxTransformPipeline(engine)

    private fun createContext(
        source: String = "test-source",
        idempotencyKey: String? = "test-key",
        eventType: String? = "test.event"
    ) = InboxTransformContext(
        messageId = UUID.randomUUID(),
        source = source,
        idempotencyKey = idempotencyKey,
        eventType = eventType,
        timestamp = Clock.System.now()
    )

    // === Passthrough Tests ===

    @Test
    fun `should pass through original payload when no transform configured`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
            put("name", "test")
        }

        val result = pipeline.transform(payload, null, createContext())

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(payload, result.payload)
    }

    @Test
    fun `should pass through original payload when transform is null`() = runTest {
        val payload = buildJsonObject {
            put("data", "value")
        }

        val result = pipeline.transform(payload, null, createContext())

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(payload, result.payload)
    }

    // === Simple Transform Tests ===

    @Test
    fun `should apply simple field mapping transform`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
            put("fullName", "John Doe")
        }
        val transform = TransformConfig(
            expression = """{ "userId": id, "name": fullName }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val expected = buildJsonObject {
            put("userId", "123")
            put("name", "John Doe")
        }
        assertEquals(expected, result.payload)
    }

    @Test
    fun `should apply transform with nested object creation`() = runTest {
        val payload = buildJsonObject {
            put("firstName", "John")
            put("lastName", "Doe")
            put("email", "john@example.com")
        }
        val transform = TransformConfig(
            expression = """{ "user": { "name": firstName & " " & lastName, "contact": email } }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        val userObj = resultObj["user"] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("John Doe"), userObj["name"])
        assertEquals(JsonPrimitive("john@example.com"), userObj["contact"])
    }

    // === Context Variable Tests ===

    @Test
    fun `should inject source context variable`() = runTest {
        val payload = buildJsonObject {
            put("data", "value")
        }
        val transform = TransformConfig(
            expression = """{ "data": data, "source": ${"$"}source }"""
        )

        val result = pipeline.transform(payload, transform, createContext(source = "stripe-webhooks"))

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("stripe-webhooks"), resultObj["source"])
    }

    @Test
    fun `should inject messageId context variable`() = runTest {
        val payload = buildJsonObject {
            put("data", "value")
        }
        val transform = TransformConfig(
            expression = """{ "hasMessageId": ${"$"}messageId != null }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(true), resultObj["hasMessageId"])
    }

    @Test
    fun `should inject timestamp context variable`() = runTest {
        val payload = buildJsonObject {
            put("data", "value")
        }
        val transform = TransformConfig(
            expression = """{ "hasTimestamp": ${"$"}timestamp != null }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(true), resultObj["hasTimestamp"])
    }

    // === Error Strategy Tests ===

    @Test
    fun `should return original payload when onError is Skip`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
        }
        val transform = TransformConfig(
            expression = """invalidExpression!!!""", // Invalid JSONata
            onError = TransformErrorStrategy.Skip
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(payload, result.payload)
    }

    @Test
    fun `should return Rejected when onError is Fail`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
        }
        val transform = TransformConfig(
            expression = """${"$"}nonExistentFunction()""", // Invalid function
            onError = TransformErrorStrategy.Fail
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Rejected>(result)
        assertTrue(result.reason.isNotEmpty())
    }

    @Test
    fun `should return Rejected when onError is Dead`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
        }
        val transform = TransformConfig(
            expression = """${"$"}unknownFunction()""", // Calling undefined function will fail
            onError = TransformErrorStrategy.Dead
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Rejected>(result)
        assertTrue(result.reason.isNotEmpty())
    }

    // === Timeout Tests ===

    @Test
    fun `should respect configured timeout`() = runTest {
        val payload = buildJsonObject {
            put("id", "123")
        }
        // Simple expression that should complete quickly
        val transform = TransformConfig(
            expression = """{ "id": id }""",
            timeoutMs = 1000
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
    }

    // === Complex Transform Tests ===

    @Test
    fun `should handle array transformations`() = runTest {
        val payload = Json.parseToJsonElement(
            """
            {
                "items": [
                    {"name": "Item 1", "price": 10},
                    {"name": "Item 2", "price": 20},
                    {"name": "Item 3", "price": 30}
                ]
            }
            """.trimIndent()
        )

        val transform = TransformConfig(
            expression = """{ "itemNames": items.name, "total": ${"$"}sum(items.price) }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(60), resultObj["total"])
    }

    @Test
    fun `should handle null values in payload`() = runTest {
        val payload = Json.parseToJsonElement(
            """
            {
                "id": "123",
                "optional": null
            }
            """.trimIndent()
        )

        val transform = TransformConfig(
            expression = """{ "id": id, "hasOptional": optional != null }"""
        )

        val result = pipeline.transform(payload, transform, createContext())

        assertIs<InboxTransformResult.Success>(result)
        val resultObj = result.payload as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("123"), resultObj["id"])
        assertEquals(JsonPrimitive(false), resultObj["hasOptional"])
    }

    // === InboxTransformContext Tests ===

    @Test
    fun `toTransformContext should map fields correctly`() {
        val context = InboxTransformContext(
            messageId = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            source = "my-source",
            idempotencyKey = "idem-key",
            eventType = "order.created",
            timestamp = Clock.System.now()
        )

        val transformContext = context.toTransformContext()

        assertEquals(context.messageId, transformContext.messageId)
        assertEquals("order.created", transformContext.topic)
        // A source transform runs once, so no delivery has failed yet. The meaning of
        // ${'$'}attempt is therefore the same as on the outbox: the number of failed deliveries.
        assertEquals(0, transformContext.attempt)
        assertEquals(context.timestamp, transformContext.timestamp)
        assertEquals("my-source", transformContext.source)
    }

    @Test
    fun `toTransformContext should use empty string when eventType is null`() {
        val context = InboxTransformContext(
            messageId = UUID.randomUUID(),
            source = "my-source",
            idempotencyKey = null,
            eventType = null,
            timestamp = Clock.System.now()
        )

        val transformContext = context.toTransformContext()

        assertEquals("", transformContext.topic)
    }

    @Test
    fun `an inbox transform reads the received headers`() = runTest {
        val payload = buildJsonObject { put("id", "123") }
        val context = createContext().copy(headers = mapOf("x-tenant" to "acme"))

        val result = pipeline.transform(
            payload,
            TransformConfig(expression = """{ "tenant": ${'$'}headers.`x-tenant` }"""),
            context
        )

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(buildJsonObject { put("tenant", "acme") }, result.payload)
    }

    @Test
    fun `an inbox transform reads the idempotency key and the event type`() = runTest {
        val payload = buildJsonObject { put("id", "123") }

        val result = pipeline.transform(
            payload,
            TransformConfig(expression = """{ "k": ${'$'}idempotencyKey, "t": ${'$'}eventType }"""),
            createContext(idempotencyKey = "idem-1", eventType = "order.created")
        )

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(
            buildJsonObject {
                put("k", "idem-1")
                put("t", "order.created")
            },
            result.payload
        )
    }

    @Test
    fun `an inbox transform reads a missing idempotency key and event type as null`() = runTest {
        val payload = buildJsonObject { put("id", "123") }

        val result = pipeline.transform(
            payload,
            TransformConfig(expression = """{ "k": ${'$'}idempotencyKey, "t": ${'$'}eventType }"""),
            createContext(idempotencyKey = null, eventType = null)
        )

        assertIs<InboxTransformResult.Success>(result)
        assertEquals(
            buildJsonObject {
                put("k", JsonNull)
                put("t", JsonNull)
            },
            result.payload
        )
    }
}
