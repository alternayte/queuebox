package org.nxtspec

import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NatsInboxConsumerTest {

    // JetStream is not on by default, so the container starts the server with `-js`.
    private val nats: GenericContainer<*> = GenericContainer("nats:2.10-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))

    private lateinit var servers: String

    @BeforeAll
    fun start() {
        nats.start()
        servers = "nats://${nats.host}:${nats.getMappedPort(4222)}"
    }

    @AfterAll
    fun stop() {
        nats.stop()
    }

    /** QueueBox never creates a stream, so every test creates the one it consumes. */
    private fun createStream(subjectPrefix: String): String {
        val stream = "stream-${UUID.randomUUID().toString().replace("-", "")}"
        connect(servers, null, null, null, 10000).use { connection ->
            connection.jetStreamManagement().addStream(
                StreamConfiguration.builder()
                    .name(stream)
                    .subjects("$subjectPrefix.>")
                    .storageType(StorageType.Memory)
                    .build()
            )
        }
        return stream
    }

    private fun publish(subject: String, body: String, headers: Map<String, String> = emptyMap()) {
        connect(servers, null, null, null, 10000).use { connection ->
            val natsHeaders = Headers()
            headers.forEach { (name, value) -> natsHeaders.add(name, value) }
            connection.jetStream().publish(subject, natsHeaders, body.toByteArray())
        }
    }

    private fun consumerConfig(stream: String, filter: String) = NatsConsumerConfig(
        sourceName = "orders",
        servers = servers,
        stream = stream,
        durable = "durable${UUID.randomUUID().toString().replace("-", "")}",
        filterSubject = filter,
        idempotencyKeyPath = "$.id",
        aggregateIdPath = "$.customerId",
        eventTypePath = "$.type",
        ackWaitMs = 5000
    )

    @Test
    fun `a message becomes one inbox row with its aggregate and event type`() = runBlocking {
        val prefix = "orders${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        publish("$prefix.created", """{"id":"evt-1","customerId":"cus-1","type":"order.created"}""")

        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = NatsInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(stream, "$prefix.>")
        )

        try {
            consumer.start()
            withTimeout(60000) { while (stored.isEmpty()) delay(100) }

            val message = assertNotNull(stored["evt-1"])
            assertEquals("orders", message.source)
            assertEquals("cus-1", message.aggregateId)
            assertEquals("order.created", message.eventType)
            assertEquals("evt-1", message.payload.jsonObject["id"]?.jsonPrimitive?.content)
        } finally {
            consumer.stop()
        }
    }

    @Test
    fun `the message header wins over the body path for the idempotency key`() = runBlocking {
        val prefix = "orders${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        publish(
            "$prefix.created",
            """{"id":"from-body","type":"order.created"}""",
            mapOf("x-idempotency-key" to "from-header")
        )

        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = NatsInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(stream, "$prefix.>")
        )

        try {
            consumer.start()
            withTimeout(60000) { while (stored.isEmpty()) delay(100) }
            assertTrue(stored.containsKey("from-header"), "the header must win, got ${stored.keys}")
        } finally {
            consumer.stop()
        }
    }

    @Test
    fun `a failed store is redelivered rather than acknowledged`() = runBlocking {
        val prefix = "orders${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        publish("$prefix.created", """{"id":"evt-retry","type":"order.created"}""")

        val attempts = AtomicInteger(0)
        val consumer = NatsInboxConsumer(
            storeMessage = {
                // The first attempt fails. An acknowledgement here would lose the message.
                if (attempts.incrementAndGet() == 1) InboxResult.Error("the database is down") else InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(stream, "$prefix.>")
        )

        try {
            consumer.start()
            withTimeout(60000) { while (attempts.get() < 2) delay(100) }
            assertTrue(attempts.get() >= 2, "a negative acknowledgement must bring the message back")
        } finally {
            consumer.stop()
        }
    }

    @Test
    fun `an acknowledged message is not delivered again after a restart`() = runBlocking {
        val prefix = "orders${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        val durable = "durable${UUID.randomUUID().toString().replace("-", "")}"
        publish("$prefix.created", """{"id":"evt-once","type":"order.created"}""")

        val base = consumerConfig(stream, "$prefix.>").copy(durable = durable)

        val first = AtomicInteger(0)
        val one = NatsInboxConsumer(
            storeMessage = {
                first.incrementAndGet()
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = base
        )
        one.start()
        try {
            withTimeout(60000) { while (first.get() == 0) delay(100) }
        } finally {
            one.stop()
        }

        val second = AtomicInteger(0)
        val two = NatsInboxConsumer(
            storeMessage = {
                second.incrementAndGet()
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = base
        )
        two.start()
        try {
            // Longer than ackWait, so a message that was never acknowledged would return.
            delay(10000)
            assertEquals(0, second.get(), "an acknowledged message must not return")
        } finally {
            two.stop()
        }
    }

    @Test
    fun `a message that is not JSON is stored dead and acknowledged`() = runBlocking {
        val prefix = "orders${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        publish("$prefix.created", "this is not json")
        publish("$prefix.created", """{"id":"evt-after-bad","type":"order.created"}""")

        val dead = ConcurrentHashMap<String, InboxMessage>()
        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = NatsInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(stream, "$prefix.>"),
            storeDeadMessage = { message ->
                dead[message.idempotencyKey] = message
                InboxResult.Stored
            }
        )

        try {
            consumer.start()
            withTimeout(60000) { while (!stored.containsKey("evt-after-bad")) delay(100) }
            assertEquals(1, dead.size, "the unreadable message must be stored dead")
            assertTrue(dead.keys.single().startsWith("sha256:"), "its key must be a digest of the body")

            // It was acknowledged, so it must not come back after the acknowledgement wait.
            delay(7000)
            assertEquals(1, dead.size, "the dead message must not be delivered again")
        } finally {
            consumer.stop()
        }
    }
}
