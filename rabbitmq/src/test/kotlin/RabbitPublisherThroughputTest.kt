package org.nxtspec

import com.rabbitmq.client.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * F-020, F-021 and F-022. The publisher must reuse one channel per destination, report a
 * measured throughput figure, and fail a publish that the broker returns as unroutable.
 */
@Tag("integration")
class RabbitPublisherThroughputTest {

    /** Counts every channel that the publisher opens. */
    private class CountingConnection(url: String) : RabbitConnection(url) {
        val channelCount = AtomicInteger(0)
        override suspend fun getChannel(): Channel {
            channelCount.incrementAndGet()
            return super.getChannel()
        }
    }

    private val amqpUrl: String get() = RabbitTestContainer.amqpUrl

    private var publisher: RabbitPublisher? = null

    @AfterEach
    fun teardown() = runBlocking {
        publisher?.close()
        publisher = null
    }

    /** Declare the exchange and bind a queue, so the published message is routable. */
    private fun bindQueue(exchange: String, queue: String, routingKey: String) {
        val factory = com.rabbitmq.client.ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.exchangeDeclare(exchange, "topic", true)
                channel.queueDeclare(queue, false, false, true, null)
                channel.queueBind(queue, exchange, routingKey)
            }
        }
    }

    @Test
    fun `publish 1000 messages opens fewer than 10 channels`() = runBlocking {
        val counting = CountingConnection(amqpUrl)
        val connections = ConcurrentHashMap<String, RabbitConnection>()
        connections["throughput-test"] = counting
        val target = RabbitPublisher(connections)
        publisher = target

        val destination = Destination.RabbitMQ(
            name = "throughput-test",
            url = amqpUrl,
            exchange = "throughput-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )

        bindQueue("throughput-exchange", "throughput-queue", "orders.created")

        val count = 1000
        val start = System.nanoTime()
        for (index in 0 until count) {
            val message = OutboxMessage(
                id = UUID.randomUUID(),
                topic = "orders.created",
                payload = JsonObject(mapOf("index" to JsonPrimitive(index)))
            )
            val result = target.publish(message, destination)
            assertTrue(result.isSuccess, "Publish $index failed: ${result.exceptionOrNull()?.message}")
        }
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        val perSecond = count * 1000.0 / elapsedMillis.coerceAtLeast(1)
        println(
            "F-021 throughput: $count messages in $elapsedMillis ms, ${"%.1f".format(perSecond)} messages per second"
        )

        assertTrue(
            counting.channelCount.get() < 10,
            "Expected fewer than 10 channels, got ${counting.channelCount.get()}"
        )
    }

    @Test
    fun `publish to topic exchange with no matching binding fails`() = runBlocking {
        val target = RabbitPublisher()
        publisher = target

        val destination = Destination.RabbitMQ(
            name = "unroutable-test",
            url = amqpUrl,
            exchange = "unroutable-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "no.binding.matches.this"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "orders.created",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("12345")))
        )

        val result = target.publish(message, destination)

        assertTrue(
            result.isFailure,
            "An unroutable publish must fail, so the outbox row is not marked sent"
        )
    }
}
