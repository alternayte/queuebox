package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotencyExtractorTest {

    private val extractor = IdempotencyExtractor()

    @Test
    fun `should extract root field when simple path`() {
        val payload = Json.parseToJsonElement("""{"id": "abc123"}""")

        val result = extractor.extract(payload, "$.id")

        assertTrue(result.isSuccess)
        assertEquals("abc123", result.getOrNull())
    }

    @Test
    fun `should extract nested field when nested path`() {
        val payload = Json.parseToJsonElement("""{"data": {"transaction_id": "tx-456"}}""")

        val result = extractor.extract(payload, "$.data.transaction_id")

        assertTrue(result.isSuccess)
        assertEquals("tx-456", result.getOrNull())
    }

    @Test
    fun `should extract deeply nested field`() {
        val payload = Json.parseToJsonElement("""{"level1": {"level2": {"level3": {"value": "deep"}}}}""")

        val result = extractor.extract(payload, "$.level1.level2.level3.value")

        assertTrue(result.isSuccess)
        assertEquals("deep", result.getOrNull())
    }

    @Test
    fun `should extract from array when array index path`() {
        val payload = Json.parseToJsonElement("""{"items": [{"id": "first"}, {"id": "second"}]}""")

        val result = extractor.extract(payload, "$.items[0].id")

        assertTrue(result.isSuccess)
        assertEquals("first", result.getOrNull())
    }

    @Test
    fun `should extract from second array element`() {
        val payload = Json.parseToJsonElement("""{"items": [{"id": "first"}, {"id": "second"}]}""")

        val result = extractor.extract(payload, "$.items[1].id")

        assertTrue(result.isSuccess)
        assertEquals("second", result.getOrNull())
    }

    @Test
    fun `should return failure when path not found`() {
        val payload = Json.parseToJsonElement("""{"foo": "bar"}""")

        val result = extractor.extract(payload, "$.nonexistent")

        assertTrue(result.isFailure)
        assertIs<ExtractionException>(result.exceptionOrNull())
    }

    @Test
    fun `should return failure when nested path not found`() {
        val payload = Json.parseToJsonElement("""{"data": {"id": "123"}}""")

        val result = extractor.extract(payload, "$.data.missing.field")

        assertTrue(result.isFailure)
        assertIs<ExtractionException>(result.exceptionOrNull())
    }

    @Test
    fun `should handle numeric values when extracting numbers`() {
        val payload = Json.parseToJsonElement("""{"count": 42}""")

        val result = extractor.extract(payload, "$.count")

        assertTrue(result.isSuccess)
        assertEquals("42", result.getOrNull())
    }

    @Test
    fun `should handle floating point values`() {
        val payload = Json.parseToJsonElement("""{"price": 19.99}""")

        val result = extractor.extract(payload, "$.price")

        assertTrue(result.isSuccess)
        assertEquals("19.99", result.getOrNull())
    }

    @Test
    fun `should handle boolean values`() {
        val payload = Json.parseToJsonElement("""{"active": true}""")

        val result = extractor.extract(payload, "$.active")

        assertTrue(result.isSuccess)
        assertEquals("true", result.getOrNull())
    }

    @Test
    fun `should extract from empty string value`() {
        val payload = Json.parseToJsonElement("""{"name": ""}""")

        val result = extractor.extract(payload, "$.name")

        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }

    @Test
    fun `should handle special characters in values`() {
        val payload = Json.parseToJsonElement("""{"message": "Hello, \"world\"!"}""")

        val result = extractor.extract(payload, "$.message")

        assertTrue(result.isSuccess)
        assertEquals("Hello, \"world\"!", result.getOrNull())
    }

    @Test
    fun `should return failure when extracting from empty object`() {
        val payload = Json.parseToJsonElement("""{}""")

        val result = extractor.extract(payload, "$.id")

        assertTrue(result.isFailure)
        assertIs<ExtractionException>(result.exceptionOrNull())
    }

    @Test
    fun `should return failure when array index out of bounds`() {
        val payload = Json.parseToJsonElement("""{"items": [{"id": "only"}]}""")

        val result = extractor.extract(payload, "$.items[5].id")

        assertTrue(result.isFailure)
        assertIs<ExtractionException>(result.exceptionOrNull())
    }

    @Test
    fun `should extract from complex nested structure`() {
        val payload = Json.parseToJsonElement("""{
            "order": {
                "customer": {
                    "addresses": [
                        {"type": "billing", "id": "addr-1"},
                        {"type": "shipping", "id": "addr-2"}
                    ]
                }
            }
        }""")

        val result = extractor.extract(payload, "$.order.customer.addresses[1].id")

        assertTrue(result.isSuccess)
        assertEquals("addr-2", result.getOrNull())
    }

    // --- F-025: one JSON parse per message ---

    @Test
    fun `should parse the payload once for many paths`() {
        var parseCount = 0
        val counting = IdempotencyExtractor { payload ->
            parseCount++
            com.jayway.jsonpath.JsonPath.parse(Json.encodeToString(JsonElement.serializer(), payload))
        }
        val payload = Json.parseToJsonElement(
            """{"id": "a", "b": "2", "c": "3", "d": "4", "e": "5"}"""
        )

        val values = counting.extractAll(
            payload,
            mapOf(
                "one" to "$.id",
                "two" to "$.b",
                "three" to "$.c",
                "four" to "$.d",
                "five" to "$.e"
            )
        )

        assertEquals(1, parseCount)
        assertEquals("a", values["one"])
        assertEquals("5", values["five"])
    }

    @Test
    fun `should parse the payload once for one path`() {
        var parseCount = 0
        val counting = IdempotencyExtractor { payload ->
            parseCount++
            com.jayway.jsonpath.JsonPath.parse(Json.encodeToString(JsonElement.serializer(), payload))
        }
        val payload = Json.parseToJsonElement("""{"id": "a"}""")

        counting.extractAll(payload, mapOf("one" to "$.id"))

        assertEquals(1, parseCount)
    }

    @Test
    fun `should return null for a path that is not found`() {
        val payload = Json.parseToJsonElement("""{"id": "a"}""")

        val values = extractor.extractAll(payload, mapOf("one" to "$.id", "two" to "$.missing"))

        assertEquals("a", values["one"])
        assertNull(values["two"])
    }

    @Test
    fun `should not parse the payload when no path is configured`() {
        var parseCount = 0
        val counting = IdempotencyExtractor { payload ->
            parseCount++
            com.jayway.jsonpath.JsonPath.parse(Json.encodeToString(JsonElement.serializer(), payload))
        }

        val values = counting.extractAll(Json.parseToJsonElement("""{"id": "a"}"""), emptyMap())

        assertEquals(0, parseCount)
        assertTrue(values.isEmpty())
    }
}
