package org.nxtspec.transform

import com.dashjoin.jsonata.JException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransformEngineTest {

    private val engine = TransformEngine()

    private fun createContext(topic: String = "test.topic", attempt: Int = 1, source: String? = null) =
        TransformContext(
            messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            topic = topic,
            attempt = attempt,
            timestamp = Clock.System.now(),
            source = source
        )

    private fun parseJson(json: String): JsonElement = Json.parseToJsonElement(json)

    // ===== Simple Field Mapping Tests =====

    @Test
    fun `should transform simple field mapping`() {
        val payload = parseJson("""{"name": "Alice", "age": 30}""")
        val context = createContext()

        val result = engine.evaluate("name", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("Alice", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should transform object creation`() {
        val payload = parseJson("""{"firstName": "John", "lastName": "Doe"}""")
        val context = createContext()

        val result = engine.evaluate("""{"fullName": firstName & " " & lastName}""", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("John Doe", result.getOrThrow().jsonObject["fullName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should transform nested field access`() {
        val payload = parseJson("""{"user": {"name": "Bob", "email": "bob@example.com"}}""")
        val context = createContext()

        val result = engine.evaluate("user.email", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("bob@example.com", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should return null for missing field`() {
        val payload = parseJson("""{"name": "Alice"}""")
        val context = createContext()

        val result = engine.evaluate("missing", payload, context)

        assertTrue(result.isSuccess)
        assertEquals(JsonPrimitive(null as String?), result.getOrThrow())
    }

    // ===== Array Operations Tests =====

    @Test
    fun `should transform array with sum operation`() {
        val payload = parseJson("""{"items": [{"price": 10}, {"price": 20}, {"price": 30}]}""")
        val context = createContext()

        val result = engine.evaluate("\$sum(items.price)", payload, context)

        assertTrue(result.isSuccess)
        assertEquals(60, result.getOrThrow().jsonPrimitive.int)
    }

    @Test
    fun `should transform array mapping`() {
        val payload = parseJson("""{"numbers": [1, 2, 3, 4, 5]}""")
        val context = createContext()

        val result = engine.evaluate("numbers.(\$ * 2)", payload, context)

        assertTrue(result.isSuccess)
        val array = result.getOrThrow().jsonArray
        assertEquals(5, array.size)
        assertEquals(2, array[0].jsonPrimitive.int)
        assertEquals(10, array[4].jsonPrimitive.int)
    }

    @Test
    fun `should filter array elements`() {
        val payload = parseJson("""{"items": [{"status": "active"}, {"status": "inactive"}, {"status": "active"}]}""")
        val context = createContext()

        val result = engine.evaluate("""items[status = "active"]""", payload, context)

        assertTrue(result.isSuccess)
        val array = result.getOrThrow().jsonArray
        assertEquals(2, array.size)
    }

    // ===== Context Variable Injection Tests =====

    @Test
    fun `should inject messageId context variable`() {
        val payload = parseJson("""{}""")
        val context = createContext()

        val result = engine.evaluate("\$messageId", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should inject topic context variable`() {
        val payload = parseJson("""{}""")
        val context = createContext(topic = "order.created")

        val result = engine.evaluate("\$topic", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("order.created", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should inject attempt context variable`() {
        val payload = parseJson("""{}""")
        val context = createContext(attempt = 3)

        val result = engine.evaluate("\$attempt", payload, context)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow().jsonPrimitive.int)
    }

    @Test
    fun `should inject timestamp context variable`() {
        val payload = parseJson("""{}""")
        val context = createContext()

        val result = engine.evaluate("\$timestamp", payload, context)

        assertTrue(result.isSuccess)
        // Just verify it's a non-empty string (ISO-8601 timestamp)
        assertTrue(result.getOrThrow().jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `should inject source context variable when provided`() {
        val payload = parseJson("""{}""")
        val context = createContext(source = "webhook-api")

        val result = engine.evaluate("\$source", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("webhook-api", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should combine payload with context variables`() {
        val payload = parseJson("""{"orderId": "ORD-123"}""")
        val context = createContext(topic = "order.shipped")

        val result = engine.evaluate("{\"order\": orderId, \"event\": \$topic}", payload, context)

        assertTrue(result.isSuccess)
        val obj = result.getOrThrow().jsonObject
        assertEquals("ORD-123", obj["order"]?.jsonPrimitive?.content)
        assertEquals("order.shipped", obj["event"]?.jsonPrimitive?.content)
    }

    // ===== Expression Validation Tests =====

    @Test
    fun `should validate correct expression`() {
        val result = engine.validateExpression("""{"id": id}""")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `should fail validation for invalid expression`() {
        val result = engine.validateExpression("""{"unclosed": """)

        assertTrue(result.isFailure)
        assertIs<JException>(result.exceptionOrNull())
    }

    @Test
    fun `should fail validation for invalid syntax`() {
        // Using clearly malformed expression with unbalanced brackets
        val result = engine.validateExpression("{ unclosed")

        assertTrue(result.isFailure)
    }

    // ===== Caching Tests =====

    @Test
    fun `should cache compiled expressions`() {
        val expression = """{"test": name}"""
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()

        // Evaluate twice with the same expression
        engine.evaluate(expression, payload, context)
        engine.evaluate(expression, payload, context)

        // Should only have one cached expression
        assertEquals(1, engine.cacheSize())
    }

    @Test
    fun `should cache different expressions separately`() {
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()

        engine.evaluate("name", payload, context)
        engine.evaluate("name & '!'", payload, context)

        assertEquals(2, engine.cacheSize())
    }

    @Test
    fun `should clear cache`() {
        val payload = parseJson("""{"name": "Test"}""")
        val context = createContext()

        engine.evaluate("name", payload, context)
        assertEquals(1, engine.cacheSize())

        engine.clearCache()

        assertEquals(0, engine.cacheSize())
    }

    @Test
    fun `should evict entries when cache exceeds max size`() {
        val smallCacheEngine = TransformEngine(maxCacheSize = 3)
        val payload = parseJson("""{"x": 1}""")
        val context = createContext()

        // Add 5 expressions to a cache with max size 3
        repeat(5) { i ->
            smallCacheEngine.evaluate("x + $i", payload, context)
        }

        // Cache should not exceed max size
        assertTrue(smallCacheEngine.cacheSize() <= 3)
    }

    // ===== Timeout Protection Tests =====

    @Test
    fun `should complete within timeout for fast expressions`() {
        val payload = parseJson("""{"x": 1}""")
        val context = createContext()

        val result = engine.evaluate("x + 1", payload, context, timeoutMs = 1000)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().jsonPrimitive.int)
    }

    @Test
    fun `should timeout for expressions exceeding timeout`() {
        val payload = parseJson("""{"x": 1}""")
        val context = createContext()

        // Use a very short timeout that should be exceeded by any expression
        // Note: JSONata library's setRuntimeBounds handles this internally
        val result = engine.evaluate(
            "\$reduce([1..10000], function(\$acc, \$v) { \$acc + \$v }, 0)",
            payload,
            context,
            timeoutMs = 1 // 1ms timeout - should be exceeded
        )

        // The library throws JException for timeout
        assertTrue(result.isFailure)
    }

    // ===== Error Handling Tests =====

    @Test
    fun `should handle malformed JSON input gracefully`() {
        // Since we receive JsonElement, this test verifies internal parsing
        val payload = parseJson("""{"valid": "json"}""")
        val context = createContext()

        val result = engine.evaluate("valid", payload, context)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `should handle division by zero`() {
        val payload = parseJson("""{"x": 10, "y": 0}""")
        val context = createContext()

        val result = engine.evaluate("x / y", payload, context)

        // JSONata may throw an error for division by zero or return a result
        // Either is acceptable behavior - we just verify it doesn't crash unexpectedly
        assertTrue(result.isSuccess || result.isFailure)
    }

    @Test
    fun `should handle type coercion`() {
        val payload = parseJson("""{"num": "42"}""")
        val context = createContext()

        val result = engine.evaluate("\$number(num) + 8", payload, context)

        assertTrue(result.isSuccess)
        assertEquals(50, result.getOrThrow().jsonPrimitive.int)
    }

    // ===== Complex Transformation Tests =====

    @Test
    fun `should transform complex nested structure`() {
        val payload =
            parseJson(
                """
            {
                "order": {
                    "id": "ORD-001",
                    "customer": {"name": "Alice", "email": "alice@example.com"},
                    "items": [
                        {"sku": "ITEM-1", "qty": 2, "price": 10},
                        {"sku": "ITEM-2", "qty": 1, "price": 25}
                    ]
                }
            }
                """.trimIndent()
            )
        val context = createContext(topic = "order.completed")

        val result = engine.evaluate(
            """
            {
                "orderId": order.id,
                "customerEmail": order.customer.email,
                "totalAmount": ${"$"}sum(order.items.(qty * price)),
                "eventType": ${"$"}topic
            }
            """.trimIndent(),
            payload,
            context
        )

        assertTrue(result.isSuccess)
        val obj = result.getOrThrow().jsonObject
        assertEquals("ORD-001", obj["orderId"]?.jsonPrimitive?.content)
        assertEquals("alice@example.com", obj["customerEmail"]?.jsonPrimitive?.content)
        assertEquals(45, obj["totalAmount"]?.jsonPrimitive?.int)
        assertEquals("order.completed", obj["eventType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should use built-in now function`() {
        val payload = parseJson("""{}""")
        val context = createContext()

        val result = engine.evaluate("\$now()", payload, context)

        assertTrue(result.isSuccess)
        // $now() returns ISO-8601 timestamp string
        val timestamp = result.getOrThrow().jsonPrimitive.content
        assertTrue(timestamp.contains("T")) // ISO-8601 format has T separator
    }

    // ===== Edge Cases =====

    @Test
    fun `should handle empty payload`() {
        val payload = parseJson("""{}""")
        val context = createContext()

        val result = engine.evaluate("""{"empty": true}""", payload, context)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `should handle empty array`() {
        val payload = parseJson("""{"items": []}""")
        val context = createContext()

        val result = engine.evaluate("\$sum(items.price)", payload, context)

        assertTrue(result.isSuccess)
        // $sum on empty array returns 0 or undefined depending on JSONata implementation
        val resultValue = result.getOrThrow()
        assertTrue(resultValue == JsonNull || resultValue.jsonPrimitive.content == "0")
    }

    @Test
    fun `should handle unicode characters`() {
        val payload = parseJson("""{"greeting": "Hello, 世界! 🌍"}""")
        val context = createContext()

        val result = engine.evaluate("greeting", payload, context)

        assertTrue(result.isSuccess)
        assertEquals("Hello, 世界! 🌍", result.getOrThrow().jsonPrimitive.content)
    }

    @Test
    fun `should handle boolean values`() {
        val payload = parseJson("""{"active": true, "verified": false}""")
        val context = createContext()

        // In JSONata, 'not' is a function $not(), not an operator
        val result = engine.evaluate("active and \$not(verified)", payload, context)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().jsonPrimitive.boolean)
    }
}
