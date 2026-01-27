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

class InboxMessageTest {

    private val testPayload = buildJsonObject {
        put("data", JsonPrimitive("test-value"))
        put("count", JsonPrimitive(100))
    }

    @Test
    fun `should generate unique UUID for each instance`() {
        val message1 = InboxMessage(source = "test", idempotencyKey = "key1", payload = testPayload)
        val message2 = InboxMessage(source = "test", idempotencyKey = "key2", payload = testPayload)

        assertNotEquals(message1.id, message2.id)
    }

    @Test
    fun `should store source correctly`() {
        val message = InboxMessage(source = "payment-service", idempotencyKey = "key", payload = testPayload)

        assertEquals("payment-service", message.source)
    }

    @Test
    fun `should store idempotencyKey correctly`() {
        val message = InboxMessage(source = "test", idempotencyKey = "unique-key-123", payload = testPayload)

        assertEquals("unique-key-123", message.idempotencyKey)
    }

    @Test
    fun `source and idempotencyKey combination should form unique identifier`() {
        val message1 = InboxMessage(source = "service-a", idempotencyKey = "key-1", payload = testPayload)
        val message2 = InboxMessage(source = "service-a", idempotencyKey = "key-1", payload = testPayload)
        val message3 = InboxMessage(source = "service-b", idempotencyKey = "key-1", payload = testPayload)
        val message4 = InboxMessage(source = "service-a", idempotencyKey = "key-2", payload = testPayload)

        // Same source + idempotencyKey produces equal composite key
        assertEquals(message1.source to message1.idempotencyKey, message2.source to message2.idempotencyKey)

        // Different source produces different composite key
        assertNotEquals(message1.source to message1.idempotencyKey, message3.source to message3.idempotencyKey)

        // Different idempotencyKey produces different composite key
        assertNotEquals(message1.source to message1.idempotencyKey, message4.source to message4.idempotencyKey)
    }

    @Test
    fun `should default state to Pending`() {
        val message = InboxMessage(source = "test", idempotencyKey = "key", payload = testPayload)

        assertEquals(MessageState.Pending, message.state)
    }

    @Test
    fun `should default eventType to null`() {
        val message = InboxMessage(source = "test", idempotencyKey = "key", payload = testPayload)

        assertNull(message.eventType)
    }

    @Test
    fun `should allow eventType to be set`() {
        val message = InboxMessage(
            source = "test",
            idempotencyKey = "key",
            eventType = "order.created",
            payload = testPayload
        )

        assertEquals("order.created", message.eventType)
    }

    @Test
    fun `should default processedAt to null`() {
        val message = InboxMessage(source = "test", idempotencyKey = "key", payload = testPayload)

        assertNull(message.processedAt)
    }

    @Test
    fun `should set createdAt timestamp on creation`() {
        val before = Clock.System.now()
        val message = InboxMessage(source = "test", idempotencyKey = "key", payload = testPayload)
        val after = Clock.System.now()

        assertTrue(message.createdAt >= before && message.createdAt <= after)
    }

    @Test
    fun `should store payload correctly`() {
        val message = InboxMessage(source = "test", idempotencyKey = "key", payload = testPayload)

        assertEquals(testPayload, message.payload)
    }

    @Test
    fun `copy should create new instance with modified fields`() {
        val original = InboxMessage(source = "original", idempotencyKey = "key", payload = testPayload)
        val processedAt = Clock.System.now()
        val copied = original.copy(state = MessageState.Sent, processedAt = processedAt)

        assertEquals(MessageState.Pending, original.state)
        assertNull(original.processedAt)
        assertEquals(MessageState.Sent, copied.state)
        assertEquals(processedAt, copied.processedAt)
        assertEquals(original.id, copied.id)
        assertEquals(original.source, copied.source)
        assertEquals(original.idempotencyKey, copied.idempotencyKey)
    }

    @Test
    fun `should serialize and deserialize correctly`() {
        val original = InboxMessage(
            source = "test-source",
            idempotencyKey = "test-key",
            eventType = "test.event",
            payload = testPayload
        )
        val json = Json.encodeToString(InboxMessage.serializer(), original)
        val deserialized = Json.decodeFromString(InboxMessage.serializer(), json)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.source, deserialized.source)
        assertEquals(original.idempotencyKey, deserialized.idempotencyKey)
        assertEquals(original.eventType, deserialized.eventType)
        assertEquals(original.payload, deserialized.payload)
        assertEquals(original.state, deserialized.state)
    }
}
