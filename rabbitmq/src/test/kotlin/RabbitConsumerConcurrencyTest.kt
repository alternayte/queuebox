package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-018 and F-019. The consumer must acknowledge from one coroutine that owns the AMQP
 * channel, and stop() must acknowledge the work that is already in flight.
 */
@Tag("integration")
class RabbitConsumerConcurrencyTest {

    private val amqpUrl: String get() = RabbitTestContainer.amqpUrl

    private lateinit var queueName: String
    private lateinit var connection: RabbitConnection
    private lateinit var extractor: IdempotencyExtractor
    private var consumer: RabbitConsumer? = null

    private val storedMessages = CopyOnWriteArrayList<InboxMessage>()
    private val duplicateCount = AtomicInteger(0)
    private val storedKeys = mutableSetOf<String>()

    /** Records the message, then waits. A real store commits and then awaits the reply. */
    private var storeDelayMillis: Long = 0

    private val mockStore: suspend (InboxMessage) -> InboxResult = { message ->
        val result = synchronized(storedKeys) {
            if (storedKeys.contains(message.idempotencyKey)) {
                duplicateCount.incrementAndGet()
                InboxResult.Duplicate
            } else {
                storedKeys.add(message.idempotencyKey)
                storedMessages.add(message)
                InboxResult.Stored
            }
        }
        if (storeDelayMillis > 0) {
            delay(storeDelayMillis)
        }
        result
    }

    @BeforeEach
    fun setup() {
        storedMessages.clear()
        storedKeys.clear()
        duplicateCount.set(0)
        storeDelayMillis = 0
        queueName = "concurrency-queue-${UUID.randomUUID()}"
        extractor = IdempotencyExtractor()
        connection = RabbitConnection(amqpUrl)
        withControlChannel { it.queueDeclare(queueName, false, false, false, null) }
    }

    @AfterEach
    fun teardown() {
        runBlocking {
            consumer?.stop()
            connection.close()
        }
        withControlChannel { it.queueDelete(queueName) }
    }

    private fun <T> withControlChannel(block: (com.rabbitmq.client.Channel) -> T): T {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                return block(channel)
            }
        }
    }

    private fun publishMessages(count: Int) {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                for (index in 0 until count) {
                    val props = AMQP.BasicProperties.Builder()
                        .messageId("message-$index")
                        .build()
                    channel.basicPublish("", queueName, props, """{"id": "key-$index"}""".toByteArray())
                }
            }
        }
    }

    private fun readyMessageCount(): Int = withControlChannel { it.queueDeclarePassive(queueName).messageCount }

    @Test
    fun `500 messages with prefetch 50 are all acknowledged on an open channel`() = runBlocking {
        val total = 500
        publishMessages(total)

        val config = RabbitConsumerConfig(
            queueName = queueName,
            sourceName = "concurrency-source",
            prefetchCount = 50,
            idempotencyKeyPath = "$.id"
        )
        val target = RabbitConsumer(connection, mockStore, extractor, config)
        consumer = target
        target.start()

        val deadline = System.currentTimeMillis() + 60_000
        while (storedMessages.size < total && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        assertEquals(total, storedMessages.size, "Every message must be stored")
        assertTrue(target.isChannelOpen, "The AMQP channel must still be open")
        assertEquals(0, duplicateCount.get(), "No message must be redelivered")

        target.stop()
        consumer = null
        assertEquals(0, readyMessageCount(), "Every message must be acknowledged")
    }

    @Test
    fun `stop acknowledges every message that reached the store`() = runBlocking {
        val total = 200
        publishMessages(total)
        storeDelayMillis = 100

        val config = RabbitConsumerConfig(
            queueName = queueName,
            sourceName = "stop-source",
            prefetchCount = 50,
            idempotencyKeyPath = "$.id"
        )
        val target = RabbitConsumer(connection, mockStore, extractor, config)
        consumer = target
        target.start()

        delay(150)
        target.stop()
        consumer = null

        val stored = storedMessages.size
        assertTrue(stored in 1 until total, "The test needs work in flight, stored $stored of $total")
        assertEquals(
            total - stored,
            readyMessageCount(),
            "Every message that reached the store must be acknowledged"
        )
    }
}
