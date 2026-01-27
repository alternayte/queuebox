package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxMessageTest {

    private val testPayload = buildJsonObject {
        put("key", JsonPrimitive("value"))
        put("number", JsonPrimitive(42))
    }

    @Test
    fun `should generate unique UUID for each instance`() {
        val message1 = OutboxMessage(topic = "test", payload = testPayload)
        val message2 = OutboxMessage(topic = "test", payload = testPayload)

        assertNotEquals(message1.id, message2.id)
    }

    @Test
    fun `should default state to Pending`() {
        val message = OutboxMessage(topic = "test", payload = testPayload)

        assertEquals(MessageState.Pending, message.state)
    }

    @Test
    fun `should default attempt counter to zero`() {
        val message = OutboxMessage(topic = "test", payload = testPayload)

        assertEquals(0, message.attempt)
    }

    @Test
    fun `should default maxAttempts to 5`() {
        val message = OutboxMessage(topic = "test", payload = testPayload)

        assertEquals(5, message.maxAttempts)
    }

    @Test
    fun `should default key to null`() {
        val message = OutboxMessage(topic = "test", payload = testPayload)

        assertNull(message.key)
    }

    @Test
    fun `should set timestamps on creation`() {
        val before = Clock.System.now()
        val message = OutboxMessage(topic = "test", payload = testPayload)
        val after = Clock.System.now()

        assertTrue(message.createdAt >= before && message.createdAt <= after)
        assertTrue(message.updatedAt >= before && message.updatedAt <= after)
        assertTrue(message.scheduledAt >= before && message.scheduledAt <= after)
    }

    @Test
    fun `should store payload correctly`() {
        val message = OutboxMessage(topic = "test", payload = testPayload)

        assertEquals(testPayload, message.payload)
    }

    @Test
    fun `copy should create new instance with modified fields`() {
        val original = OutboxMessage(topic = "original", payload = testPayload)
        val copied = original.copy(topic = "modified", attempt = 3)

        assertEquals("original", original.topic)
        assertEquals(0, original.attempt)
        assertEquals("modified", copied.topic)
        assertEquals(3, copied.attempt)
        assertEquals(original.id, copied.id)
        assertEquals(original.payload, copied.payload)
    }

    @Test
    fun `copy should preserve unmodified fields`() {
        val original = OutboxMessage(
            topic = "test",
            key = "partition-key",
            payload = testPayload,
            maxAttempts = 10
        )
        val copied = original.copy(state = MessageState.Processing)

        assertEquals(original.id, copied.id)
        assertEquals(original.topic, copied.topic)
        assertEquals(original.key, copied.key)
        assertEquals(original.payload, copied.payload)
        assertEquals(original.maxAttempts, copied.maxAttempts)
        assertEquals(original.createdAt, copied.createdAt)
        assertEquals(MessageState.Processing, copied.state)
    }

    @Test
    fun `should allow custom key to be set`() {
        val message = OutboxMessage(topic = "test", key = "custom-key", payload = testPayload)

        assertEquals("custom-key", message.key)
    }

    @Test
    fun `should serialize and deserialize correctly`() {
        val original = OutboxMessage(topic = "test", payload = testPayload)
        val json = Json.encodeToString(OutboxMessage.serializer(), original)
        val deserialized = Json.decodeFromString(OutboxMessage.serializer(), json)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.topic, deserialized.topic)
        assertEquals(original.payload, deserialized.payload)
        assertEquals(original.state, deserialized.state)
    }
}
