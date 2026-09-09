package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.nxtspec.metrics.InboxRejectionReason
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    // Keys of the rows that the consumer marked dead.
    private val deadKeys = CopyOnWriteArrayList<String>()

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
        deadKeys.clear()
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

    /** F-097: builds a consumer for a queue the test does not pre-declare. */
    private fun consumerFor(queue: String, declareQueue: Boolean = false): RabbitConsumer {
        val config = RabbitConsumerConfig(
            queueName = queue,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id",
            declareQueue = declareQueue
        )
        return RabbitConsumer(connection, mockStore, extractor, config)
    }

    /** F-097: true when the named queue exists on the broker. */
    private fun queueExists(queue: String): Boolean {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        return factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                try {
                    channel.queueDeclarePassive(queue)
                    true
                } catch (e: java.io.IOException) {
                    false
                }
            }
        }
    }

    private fun publishMessage(payload: String, headers: Map<String, Any>? = null, messageId: String? = null) {
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
        assertTrue(
            maxConcurrent.get() <= 2,
            "Max concurrent should not exceed prefetch count of 2, was ${maxConcurrent.get()}"
        )
    }

    @Test
    fun `should use a stable body digest when noIdempotencyKeyAvailable`() = runBlocking {
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
        // The last fallback is a stable digest of the body, not a random value. A random value
        // would give a redelivery a new key, and the inbox would hold a second row.
        val key = storedMessages[0].idempotencyKey
        assertTrue(key.startsWith("sha256:"), "The fallback key must be a body digest, was '$key'.")
        assertEquals(71, key.length, "The digest key must carry 64 hexadecimal characters.")
    }

    // F-097: a missing source queue must name the cause, the asymmetry with the destination
    // exchange, and the setting that fixes it, rather than the raw broker text alone.
    @Test
    fun `a missing queue names the cause, the asymmetry and the setting`() = runBlocking {
        consumer = consumerFor(queue = "no-such-queue-${UUID.randomUUID()}")

        val error = assertFailsWith<Exception> { consumer.start() }

        val message = error.message!!
        assertTrue(message.contains("does not exist"), message)
        assertTrue(message.contains("does not declare"), message)
        assertTrue(message.contains("declareQueue"), message)
    }

    // F-097 narrowing: a channel failure that is NOT a missing queue must propagate with its own
    // message, unchanged. A confidently wrong "set declareQueue" remedy for the wrong failure is
    // worse than the broker's own text, because it sends an operator to a fix that will not help.
    @Test
    fun `a non-missing-queue failure keeps its own message`() = runBlocking {
        val exclusiveQueue = "exclusive-${UUID.randomUUID()}"
        val holderFactory = ConnectionFactory().apply { setUri(amqpUrl) }
        val holderConnection = holderFactory.newConnection()
        val holderChannel = holderConnection.createChannel()
        try {
            // An exclusive queue is usable only on the connection that declared it. A second
            // connection that tries to consume it gets ACCESS_REFUSED (403), not NOT_FOUND (404).
            holderChannel.queueDeclare(exclusiveQueue, false, true, false, null)

            consumer = consumerFor(queue = exclusiveQueue)

            val error = assertFailsWith<Exception> { consumer.start() }

            val fullMessage = generateSequence<Throwable>(error) { it.cause }
                .joinToString(" | ") { it.message ?: it.toString() }
            assertTrue(
                !fullMessage.contains("declareQueue"),
                "A non-missing-queue failure must not carry the declareQueue remedy: $fullMessage"
            )
            // The broker actually answers this exclusive-queue clash with RESOURCE_LOCKED
            // (405), not ACCESS_REFUSED (403), but the point is the same: the original reply
            // text must survive unchanged, not be replaced or dropped.
            assertTrue(
                fullMessage.contains("RESOURCE_LOCKED") || fullMessage.contains("405"),
                "The original RESOURCE_LOCKED (405) failure must survive unchanged: $fullMessage"
            )
        } finally {
            holderChannel.close()
            holderConnection.close()
        }
    }

    @Test
    fun `declareQueue true creates the queue before the consumer starts`() = runBlocking {
        val queue = "declared-${UUID.randomUUID()}"
        consumer = consumerFor(queue = queue, declareQueue = true)

        consumer.start()

        assertTrue(queueExists(queue))
    }

    /** A pipeline that rejects every message. */
    private fun rejectingPipeline(): InboxTransformPipeline = mockk<InboxTransformPipeline>().also { pipeline ->
        coEvery { pipeline.transform(any(), any(), any()) } returns
            InboxTransformResult.Rejected("Transform failed")
    }

    private val rejectingTransform = TransformConfig(
        expression = "$",
        onError = TransformErrorStrategy.Fail
    )

    private fun queueDepth(): Long {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        return factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.messageCount(TEST_QUEUE)
            }
        }
    }

    @Test
    fun `transform rejection stores the original payload and marks it dead`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = mockStore,
            extractor = extractor,
            config = config,
            transformPipeline = rejectingPipeline(),
            sourceTransform = rejectingTransform,
            // The row is stored dead in one transaction. The key is recorded here, so the
            // assertions of this test stay the same.
            storeDeadMessage = { m ->
                deadKeys.add(m.idempotencyKey)
                mockStore(m)
            }
        )
        consumer.start()

        val id = "reject-${UUID.randomUUID()}"
        publishMessage("""{"id": "$id", "data": "test"}""")

        delay(1000)
        consumer.stop()

        assertEquals(1, storedMessages.size, "The rejected message must reach the inbox.")
        val stored = storedMessages[0]
        assertEquals(id, stored.idempotencyKey)
        assertEquals(
            """{"id":"$id","data":"test"}""",
            stored.payload.toString(),
            "The stored payload must be the original payload."
        )
        assertTrue(deadKeys.contains(stored.idempotencyKey), "The stored row must be marked dead.")
        assertEquals(0L, queueDepth(), "The queue must be empty after the acknowledgement.")
    }

    @Test
    fun `a body that is not JSON becomes one dead row and one acknowledgement`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = mockStore,
            extractor = extractor,
            config = config,
            metricsCollector = metricsCollector,
            storeDeadMessage = { m ->
                deadKeys.add(m.idempotencyKey)
                mockStore(m)
            }
        )
        consumer.start()

        publishMessage("not json")

        delay(1000)
        consumer.stop()

        assertEquals(1, storedMessages.size, "The unparsable body must reach the inbox as one row.")
        val stored = storedMessages[0]
        assertEquals("not json", stored.payload.jsonObject["raw"]!!.jsonPrimitive.content)
        assertTrue(deadKeys.contains(stored.idempotencyKey), "The stored row must be marked dead.")
        assertEquals(0L, queueDepth(), "The queue must be empty after the acknowledgement.")
        verify { metricsCollector.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED) }
    }

    @Test
    fun `a body that is not JSON is delivered exactly once`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        val deliveries = AtomicInteger(0)
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = mockStore,
            extractor = extractor,
            config = config,
            storeDeadMessage = { m ->
                deliveries.incrementAndGet()
                deadKeys.add(m.idempotencyKey)
                mockStore(m)
            }
        )
        consumer.start()

        publishMessage("not json")

        // A hot loop shows here. Before the fix this number grows without bound.
        delay(2000)
        consumer.stop()

        assertEquals(1, deliveries.get(), "The unparsable body must reach the dead-letter store exactly once.")
    }

    @Test
    fun `transform rejection keeps the message when the store fails`() = runBlocking {
        val throwingStore: suspend (InboxMessage) -> InboxResult = {
            throw IllegalStateException("Simulated storage failure")
        }
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = throwingStore,
            extractor = extractor,
            config = config,
            transformPipeline = rejectingPipeline(),
            sourceTransform = rejectingTransform,
            storeDeadMessage = throwingStore
        )
        consumer.start()

        publishMessage("""{"id": "keep-${UUID.randomUUID()}", "data": "test"}""")

        delay(500)
        consumer.stop()

        assertTrue(deadKeys.isEmpty(), "No row is dead, because the store failed.")
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                val response = channel.basicGet(TEST_QUEUE, false)
                assertNotNull(response, "The broker must still hold the message.")
                channel.basicNack(response.envelope.deliveryTag, false, false)
            }
        }
    }
}
