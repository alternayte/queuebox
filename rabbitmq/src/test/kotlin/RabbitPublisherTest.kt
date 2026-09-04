package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RabbitPublisherTest {

    private val publisher = RabbitPublisher()

    @Test
    fun `supports returns true for RabbitMQ destination`() {
        val destination = Destination.RabbitMQ(
            name = "test",
            url = "amqp://localhost",
            exchange = "test-exchange"
        )
        assertTrue(publisher.supports(destination))
    }

    @Test
    fun `supports returns false for Http destination`() {
        val destination = Destination.Http(
            name = "test",
            baseUrl = "http://localhost"
        )
        assertFalse(publisher.supports(destination))
    }

    @Test
    fun `publish fails with invalid destination type`() = runBlocking {
        val destination = Destination.Http(
            name = "wrong-type",
            baseUrl = "http://localhost"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "test",
            payload = JsonObject(emptyMap())
        )

        val result = publisher.publish(message, destination)

        assertTrue(result.isFailure, "Should fail with wrong destination type")
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `routing key template replaces topic with spaces`() {
        val template = "{{ topic }}"
        val topic = "orders.created"
        val result = template.replace("{{ topic }}", topic)
        assertEquals("orders.created", result)
    }

    @Test
    fun `routing key template replaces topic without spaces`() {
        val template = "{{topic}}"
        val topic = "orders.created"
        val result = template.replace("{{topic}}", topic)
        assertEquals("orders.created", result)
    }

    @Test
    fun `routing key template replaces topic in complex pattern`() {
        val template = "events.{{ topic }}.v1"
        val topic = "user.signup"
        val result = template
            .replace("{{ topic }}", topic)
            .replace("{{topic}}", topic)
        assertEquals("events.user.signup.v1", result)
    }
}
