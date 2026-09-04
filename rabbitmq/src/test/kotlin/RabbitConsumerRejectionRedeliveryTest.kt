package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the three defects of the second adversarial review gate.
 *
 * 1. A redelivery after a failed store of the dead row must still store it.
 * 2. A message with no explicit key must get a stable key, so a redelivery deduplicates.
 * 3. A missing mark-dead callback must not acknowledge the delivery.
 */
@Tag("integration")
@Testcontainers
class RabbitConsumerRejectionRedeliveryTest {

    companion object {
        private const val RABBITMQ_PORT = 5672
        private const val TEST_QUEUE = "test-rejection-redelivery-queue"
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

    private val storedMessages = CopyOnWriteArrayList<InboxMessage>()
    private val storedKeys = mutableSetOf<String>()

    /** The keys of the rows that the consumer marked dead. */
    private val deadKeys = CopyOnWriteArrayList<String>()

    private val mockStore: suspend (InboxMessage) -> InboxResult = { message ->
        synchronized(storedKeys) {
            val key = "${message.source}:${message.idempotencyKey}"
            if (storedKeys.contains(key)) {
                InboxResult.Duplicate
            } else {
                storedKeys.add(key)
                storedMessages.add(message)
                InboxResult.Stored
            }
        }
    }

    @BeforeEach
    fun setup() {
        storedMessages.clear()
        storedKeys.clear()
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
                channel.queuePurge(TEST_QUEUE)
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

    private fun queueDepth(): Long {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        return factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.messageCount(TEST_QUEUE)
            }
        }
    }

    private fun rejectingPipeline(): InboxTransformPipeline = mockk<InboxTransformPipeline>().also { pipeline ->
        coEvery { pipeline.transform(any(), any(), any()) } returns
            InboxTransformResult.Rejected("Transform failed")
    }

    private val rejectingTransform = TransformConfig(
        expression = "$",
        onError = TransformErrorStrategy.Fail
    )

    /** Defect 1: the store of the dead row is retried after a failure. */
    @Test
    fun `a redelivery stores the dead row after the first store fails`() = runBlocking {
        val attempts = AtomicInteger(0)
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
            storeDeadMessage = { message ->
                if (attempts.incrementAndGet() == 1) {
                    error("Simulated database outage")
                }
                deadKeys.add(message.idempotencyKey)
                mockStore(message)
            }
        )
        consumer.start()

        val id = "redeliver-${UUID.randomUUID()}"
        publishMessage("""{"id": "$id", "data": "test"}""")

        delay(2000)
        consumer.stop()

        assertEquals(1, storedMessages.size, "The rejected message reaches the inbox exactly once.")
        assertTrue(
            deadKeys.contains(id),
            "after redelivery: stored=${storedMessages.size} dead=${deadKeys.size}. " +
                "The redelivery must store the dead row."
        )
        assertEquals(0L, queueDepth(), "The queue must be empty after the acknowledgement.")
    }

    /**
     * Fourth review gate, defect 1. A healthy row must not die because a later, unrelated
     * message shares its idempotency key.
     *
     * The store of the dead row answers Duplicate, because the natural key exists. The consumer
     * marks nothing. A rejected message reaches `storeDeadMessage` only, and that call writes
     * state 'dead' in one transaction, so no rejected row ever waits in state 'pending'.
     */
    @Test
    fun `a healthy row survives a rejected message that shares its idempotency key`() = runBlocking {
        val id = "duplicate-${UUID.randomUUID()}"
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.id"
        )
        // An earlier, healthy event already stored a pending row for this key.
        mockStore(
            InboxMessage(
                source = "test-source",
                idempotencyKey = id,
                payload = kotlinx.serialization.json.JsonObject(emptyMap())
            )
        )

        consumer = RabbitConsumer(
            connection = connection,
            storeMessage = mockStore,
            extractor = extractor,
            config = config,
            transformPipeline = rejectingPipeline(),
            sourceTransform = rejectingTransform,
            storeDeadMessage = mockStore
        )
        consumer.start()

        publishMessage("""{"id": "$id", "data": "test"}""")

        delay(2000)
        consumer.stop()

        assertEquals(1, storedMessages.size, "The duplicate must add no second row.")
        assertEquals(
            kotlinx.serialization.json.JsonObject(emptyMap()),
            storedMessages.single().payload,
            "The healthy row must keep its own payload."
        )
        assertEquals(0L, queueDepth(), "The queue must be empty after the acknowledgement.")
    }

    /** Defect 1, second door: no dead-letter store means no acknowledgement. */
    @Test
    fun `a missing dead-letter store keeps the message in the broker`() = runBlocking {
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
            storeDeadMessage = null
        )
        consumer.start()

        publishMessage("""{"id": "nocallback-${UUID.randomUUID()}", "data": "test"}""")

        delay(1000)
        consumer.stop()

        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                val response = channel.basicGet(TEST_QUEUE, false)
                assertNotNull(response, "The broker must still hold the message, because no row is dead.")
                channel.basicNack(response.envelope.deliveryTag, false, false)
            }
        }
    }

    /** Defect 2. */
    @Test
    fun `an identical body without a key gives one stable idempotency key`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.nonexistent"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        val body = """{"data": "stable"}"""
        publishMessage(body)
        delay(500)
        publishMessage(body)
        delay(1000)
        consumer.stop()

        assertEquals(
            1,
            storedMessages.size,
            "stored rows = ${storedMessages.size}. A redelivery of the identical body must deduplicate."
        )
    }

    /** Defect 2: a different body still gives a different key. */
    @Test
    fun `a different body without a key gives a different idempotency key`() = runBlocking {
        val config = RabbitConsumerConfig(
            queueName = TEST_QUEUE,
            sourceName = "test-source",
            idempotencyKeyPath = "$.nonexistent"
        )
        consumer = RabbitConsumer(connection, mockStore, extractor, config)
        consumer.start()

        publishMessage("""{"data": "one"}""")
        publishMessage("""{"data": "two"}""")
        delay(1000)
        consumer.stop()

        assertEquals(2, storedMessages.size, "Two different bodies must give two rows.")
    }
}
