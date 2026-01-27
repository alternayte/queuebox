package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow

@Tag("integration")
@Testcontainers
class RabbitConsumerIntegrationTest {

    companion object {
        private const val RABBITMQ_PORT = 5672
        private const val TEST_QUEUE = "test-inbox-queue"
    }

    @Container
    private val rabbitMQContainer = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
        .withExposedPorts(RABBITMQ_PORT)
        .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
        .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

    private val amqpUrl: String
        get() = "amqp://guest:guest@${rabbitMQContainer.host}:${rabbitMQContainer.getMappedPort(RABBITMQ_PORT)}"

    private lateinit var connection: RabbitConnection
    private lateinit var consumer: RabbitConsumer
    private lateinit var extractor: IdempotencyExtractor

    // Track stored messages
    private val storedMessages = CopyOnWriteArrayList<InboxMessage>()
    private val storedIdempotencyKeys = mutableSetOf<String>()

    // Mock store function that simulates deduplication
    private val mockStore: suspend (InboxMessage) -> InboxResult = { message ->
        synchronized(storedIdempotencyKeys) {
            val key = "${message.source}:${message.idempotencyKey}"
            if (storedIdempotencyKeys.contains(key)) {
                InboxResult.Duplicate
            } else {
                storedIdempotencyKeys.add(key)
                storedMessages.add(message)
                InboxResult.Stored
            }
        }
    }

    @BeforeEach
    fun setup() {
        storedMessages.clear()
        storedIdempotencyKeys.clear()
        extractor = IdempotencyExtractor()
        connection = RabbitConnection(amqpUrl)
        declareTestQueue()
    }

    @AfterEach
    fun teardown() {
        runBlocking {
            if (::consumer.isInitialized) {
                consumer.stop()
            }
            connection.close()
        }
    }

    private fun declareTestQueue() {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.queueDeclare(TEST_QUEUE, false, false, false, null)
                // Purge any existing messages
                channel.queuePurge(TEST_QUEUE)
            }
        }
    }

    private fun publishMessage(
        payload: String,
        headers: Map<String, Any>? = null,
        messageId: String? = null
    ) {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                val props = AMQP.BasicProperties.Builder()
                    .headers(headers)
                    .messageId(messageId)
                    .build()
                channel.basicPublish("", TEST_QUEUE, props, payload.toByteArray())
            }
        }
    }

    @Test
    fun `message is consumed and stored in inbox`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.orderId"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val orderId = UUID.randomUUID().toString()
        publishMessage("""{"orderId": "$orderId", "data": "test"}""")

        // Wait for message to be processed
        delay(500)

        assertEquals(1, storedMessages.size, "Should have stored one message")
        assertEquals("test-source", storedMessages[0].source)
        assertEquals(orderId, storedMessages[0].idempotencyKey)
    }

    @Test
    fun `duplicate messages are deduplicated`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.orderId"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val orderId = UUID.randomUUID().toString()
        // Publish the same message twice
        publishMessage("""{"orderId": "$orderId", "data": "test1"}""")
        publishMessage("""{"orderId": "$orderId", "data": "test2"}""")

        delay(500)

        assertEquals(1, storedMessages.size, "Should have only one message due to deduplication")
    }

    @Test
    fun `idempotency key extracted from x-idempotency-key header`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val headerKey = "header-key-${UUID.randomUUID()}"
        publishMessage(
            """{"id": "payload-key", "data": "test"}""",
            headers = mapOf("x-idempotency-key" to headerKey)
        )

        delay(500)

        assertEquals(1, storedMessages.size)
        assertEquals(headerKey, storedMessages[0].idempotencyKey, "Should use header key over payload")
    }

    @Test
    fun `idempotency key falls back to messageId when path not found`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.nonExistentField"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val messageId = "msg-${UUID.randomUUID()}"
        publishMessage(
            """{"data": "test"}""",
            messageId = messageId
        )

        delay(500)

        assertEquals(1, storedMessages.size)
        assertEquals(messageId, storedMessages[0].idempotencyKey, "Should fall back to messageId")
    }

    @Test
    fun `event type extracted from x-event-type header`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val eventType = "order.created"
        publishMessage(
            """{"id": "test-${UUID.randomUUID()}", "data": "test"}""",
            headers = mapOf("x-event-type" to eventType)
        )

        delay(500)

        assertEquals(1, storedMessages.size)
        assertEquals(eventType, storedMessages[0].eventType)
    }

    @Test
    fun `storage error causes nack and requeue`() = runBlocking {
        // Use a store function that always fails
        val failingStore: suspend (InboxMessage) -> InboxResult = {
            InboxResult.Error("Simulated storage failure")
        }

        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(connection, failingStore, extractor, config)
        consumer.start()

        publishMessage("""{"id": "test-${UUID.randomUUID()}", "data": "test"}""")

        // Wait a bit then stop consumer
        delay(200)
        consumer.stop()

        // Check that message is still in queue (was requeued)
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                val response = channel.basicGet(TEST_QUEUE, false)
                assertTrue(response != null, "Message should still be in queue after nack")
                channel.basicNack(response.envelope.deliveryTag, false, false) // Clean up
            }
        }
    }

    @Test
    fun `prefetch count limits concurrent processing`() = runBlocking {
        val processedCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val currentConcurrent = AtomicInteger(0)

        val slowStore: suspend (InboxMessage) -> InboxResult = { message ->
            val current = currentConcurrent.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, current) }
            delay(100) // Simulate slow processing
            currentConcurrent.decrementAndGet()
            processedCount.incrementAndGet()
            mockStore(message)
        }

        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id",
            prefetchCount = 2
        )
        consumer = RabbitConsumer(connection, slowStore, extractor, config)
        consumer.start()

        // Publish multiple messages with unique IDs
        repeat(5) { i ->
            publishMessage("""{"id": "test-$i-${UUID.randomUUID()}", "data": "test"}""")
        }

        // Wait for all to process
        delay(1000)

        assertEquals(5, processedCount.get(), "All messages should be processed")
        assertTrue(maxConcurrent.get() <= 2, "Max concurrent should not exceed prefetch count of 2, was ${maxConcurrent.get()}")
    }

    @Test
    fun `should generateUUID when noIdempotencyKeyAvailable`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.nonexistent"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        // Publish message without id field, no x-idempotency-key header, no messageId
        publishMessage(
            """{"data": "test"}""",
            headers = null,
            messageId = null
        )

        delay(500)

        assertEquals(1, storedMessages.size, "Should have stored one message")
        // Verify it's a valid UUID format - this tests the final fallback in idempotency key extraction
        assertDoesNotThrow {
            UUID.fromString(storedMessages[0].idempotencyKey)
        }
    }
}
