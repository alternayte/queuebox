package org.nxtspec.transform

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nxtspec.TransformConfig
import org.nxtspec.TransformErrorStrategy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransformPipelineTest {

    private val engine = TransformEngine()
    private val pipeline = TransformPipeline(engine)

    private fun createContext(
        topic: String = "test.topic",
        attempt: Int = 1
    ) = TransformContext(
        messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        topic = topic,
        attempt = attempt,
        timestamp = Clock.System.now()
    )

    private fun parseJson(json: String): JsonElement = Json.parseToJsonElement(json)

    // ===== Pass-through Tests =====

    @Test
    fun `should pass through when no transforms configured`() = runBlocking {
        val payload = parseJson("""{"name": "Alice", "age": 30}""")
        val context = createContext()

        val result = pipeline.transform(
            payload = payload,
            routeTransform = null,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        assertEquals(payload, result.payload)
    }

    // ===== Route-only Transform Tests =====

    @Test
    fun `should apply route transform when only route transform configured`() = runBlocking {
        val payload = parseJson("""{"firstName": "John", "lastName": "Doe"}""")
        val context = createContext()
        val routeTransform = TransformConfig(
            expression = """{"fullName": firstName & " " & lastName}"""
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        assertEquals("John Doe", result.payload.jsonObject["fullName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should inject context variables in route transform`() = runBlocking {
        val payload = parseJson("""{"orderId": "ORD-123"}""")
        val context = createContext(topic = "order.shipped")
        val routeTransform = TransformConfig(
            expression = """{"order": orderId, "event": ${"$"}topic}"""
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        val obj = result.payload.jsonObject
        assertEquals("ORD-123", obj["order"]?.jsonPrimitive?.content)
        assertEquals("order.shipped", obj["event"]?.jsonPrimitive?.content)
    }

    // ===== Destination-only Transform Tests =====

    @Test
    fun `should apply destination transform when only destination transform configured`() = runBlocking {
        val payload = parseJson("""{"items": [{"price": 10}, {"price": 20}]}""")
        val context = createContext()
        val destinationTransform = TransformConfig(
            expression = """{"total": ${"$"}sum(items.price)}"""
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = null,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        assertEquals("30", result.payload.jsonObject["total"]?.jsonPrimitive?.content)
    }

    // ===== Chained Transform Tests =====

    @Test
    fun `should chain route then destination transforms`() = runBlocking {
        val payload = parseJson("""{"firstName": "Jane", "lastName": "Smith", "age": 25}""")
        val context = createContext()

        // Route transform: create fullName
        val routeTransform = TransformConfig(
            expression = """{"name": firstName & " " & lastName, "age": age}"""
        )

        // Destination transform: add greeting
        val destinationTransform = TransformConfig(
            expression = """{"greeting": "Hello, " & name, "age": age}"""
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        val obj = result.payload.jsonObject
        assertEquals("Hello, Jane Smith", obj["greeting"]?.jsonPrimitive?.content)
        assertEquals("25", obj["age"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should use route transform output as destination transform input`() = runBlocking {
        val payload = parseJson("""{"x": 10, "y": 20}""")
        val context = createContext()

        // Route transform: create sum field
        val routeTransform = TransformConfig(
            expression = """{"sum": x + y}"""
        )

        // Destination transform: double the sum
        val destinationTransform = TransformConfig(
            expression = """{"result": sum * 2}"""
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        assertEquals("60", result.payload.jsonObject["result"]?.jsonPrimitive?.content)
    }

    // ===== Error Strategy Tests =====

    @Test
    fun `SKIP strategy should use original payload on route transform error`() = runBlocking {
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()
        val routeTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Skip
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        assertEquals(payload, result.payload)
    }

    @Test
    fun `SKIP strategy should use post-route payload on destination transform error`() = runBlocking {
        val payload = parseJson("""{"x": 10}""")
        val context = createContext()

        val routeTransform = TransformConfig(
            expression = """{"doubled": x * 2}"""
        )

        val destinationTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Skip
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        // Should have the route-transformed payload, not the original
        assertEquals("20", result.payload.jsonObject["doubled"]?.jsonPrimitive?.content)
    }

    @Test
    fun `FAIL strategy should return Error on transform failure`() = runBlocking {
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()
        val routeTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Fail
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.Error>(result)
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun `DEAD strategy should return DeadLetter on transform failure`() = runBlocking {
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()
        val routeTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Dead
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        assertIs<TransformResult.DeadLetter>(result)
        assertTrue(result.reason.isNotEmpty())
    }

    @Test
    fun `destination FAIL strategy should return Error after successful route transform`() = runBlocking {
        val payload = parseJson("""{"x": 10}""")
        val context = createContext()

        val routeTransform = TransformConfig(
            expression = """{"doubled": x * 2}"""
        )

        val destinationTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Fail
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Error>(result)
    }

    @Test
    fun `destination DEAD strategy should return DeadLetter after successful route transform`() = runBlocking {
        val payload = parseJson("""{"x": 10}""")
        val context = createContext()

        val routeTransform = TransformConfig(
            expression = """{"doubled": x * 2}"""
        )

        val destinationTransform = TransformConfig(
            expression = "{ invalid syntax",
            onError = TransformErrorStrategy.Dead
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.DeadLetter>(result)
    }

    // ===== Timeout Tests =====

    @Test
    fun `should respect transform timeout configuration`() = runBlocking {
        val payload = parseJson("""{"x": 1}""")
        val context = createContext()
        val routeTransform = TransformConfig(
            expression = "${"$"}reduce([1..10000], function(${"$"}acc, ${"$"}v) { ${"$"}acc + ${"$"}v }, 0)",
            timeoutMs = 1,  // Very short timeout
            onError = TransformErrorStrategy.Fail
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = null,
            context = context
        )

        // Should fail due to timeout
        assertIs<TransformResult.Error>(result)
    }

    // ===== Complex Scenario Tests =====

    @Test
    fun `should handle complex payload transformation through pipeline`() = runBlocking {
        val payload = parseJson("""
            {
                "order": {
                    "id": "ORD-001",
                    "items": [
                        {"sku": "ITEM-1", "qty": 2, "price": 10},
                        {"sku": "ITEM-2", "qty": 1, "price": 25}
                    ]
                }
            }
        """.trimIndent())
        val context = createContext(topic = "order.completed")

        // Route transform: flatten and calculate total
        val routeTransform = TransformConfig(
            expression = """
                {
                    "orderId": order.id,
                    "itemCount": ${"$"}count(order.items),
                    "total": ${"$"}sum(order.items.(qty * price))
                }
            """.trimIndent()
        )

        // Destination transform: add event metadata
        val destinationTransform = TransformConfig(
            expression = """
                {
                    "orderId": orderId,
                    "summary": "Order " & orderId & " completed with " & ${"$"}string(itemCount) & " items",
                    "amount": total,
                    "event": ${"$"}topic
                }
            """.trimIndent()
        )

        val result = pipeline.transform(
            payload = payload,
            routeTransform = routeTransform,
            destinationTransform = destinationTransform,
            context = context
        )

        assertIs<TransformResult.Success>(result)
        val obj = result.payload.jsonObject
        assertEquals("ORD-001", obj["orderId"]?.jsonPrimitive?.content)
        assertEquals("Order ORD-001 completed with 2 items", obj["summary"]?.jsonPrimitive?.content)
        assertEquals("45", obj["amount"]?.jsonPrimitive?.content)
        assertEquals("order.completed", obj["event"]?.jsonPrimitive?.content)
    }
}
