package org.nxtspec

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.KafkaContainer
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaInboxConsumerTest {

    private val kafka = KafkaContainer("apache/kafka:3.8.0")

    @BeforeAll
    fun start() {
        kafka.start()
    }

    @AfterAll
    fun stop() {
        kafka.stop()
    }

    private fun producer(): KafkaProducer<String, ByteArray> {
        val properties = Properties().apply {
            setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            setProperty(ProducerConfig.ACKS_CONFIG, "all")
        }
        return KafkaProducer(properties)
    }

    private fun send(topic: String, key: String?, body: String, headers: Map<String, String> = emptyMap()) {
        producer().use { client ->
            val record = ProducerRecord(topic, key, body.toByteArray())
            headers.forEach { (name, value) -> record.headers().add(RecordHeader(name, value.toByteArray())) }
            client.send(record).get()
        }
    }

    private fun consumerConfig(topic: String, group: String) = KafkaConsumerConfig(
        sourceName = "orders",
        bootstrapServers = kafka.bootstrapServers,
        topics = listOf(topic),
        groupId = group,
        idempotencyKeyPath = "$.id",
        aggregateIdPath = "$.customerId",
        eventTypePath = "$.type"
    )

    @Test
    fun `a record becomes one inbox message with its key, aggregate and event type`() = runBlocking {
        val topic = "orders-${UUID.randomUUID()}"
        send(topic, "order-1", """{"id":"evt-1","customerId":"cus-1","type":"order.created"}""")

        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = KafkaInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, "group-${UUID.randomUUID()}")
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
    fun `the record header wins over the body path for the idempotency key`() = runBlocking {
        val topic = "orders-${UUID.randomUUID()}"
        send(
            topic,
            "order-2",
            """{"id":"from-body","type":"order.created"}""",
            mapOf("x-idempotency-key" to "from-header")
        )

        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = KafkaInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, "group-${UUID.randomUUID()}")
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
    fun `a failed store leaves the offset uncommitted, so the record is read again`() = runBlocking {
        val topic = "orders-${UUID.randomUUID()}"
        send(topic, "order-3", """{"id":"evt-retry","type":"order.created"}""")

        val attempts = AtomicInteger(0)
        val consumer = KafkaInboxConsumer(
            storeMessage = {
                // The first attempt fails. A committed offset would lose the record here.
                if (attempts.incrementAndGet() == 1) InboxResult.Error("the database is down") else InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, "group-${UUID.randomUUID()}")
        )

        try {
            consumer.start()
            withTimeout(60000) { while (attempts.get() < 2) delay(100) }
            assertTrue(attempts.get() >= 2, "the record must be read again after a failed store")
        } finally {
            consumer.stop()
        }
    }

    @Test
    fun `a committed record is not read again by the same group after a restart`() = runBlocking {
        val topic = "orders-${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        send(topic, "order-4", """{"id":"evt-once","type":"order.created"}""")

        val first = AtomicInteger(0)
        val one = KafkaInboxConsumer(
            storeMessage = {
                first.incrementAndGet()
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, group)
        )
        one.start()
        try {
            withTimeout(60000) { while (first.get() == 0) delay(100) }
        } finally {
            one.stop()
        }

        val second = AtomicInteger(0)
        val two = KafkaInboxConsumer(
            storeMessage = {
                second.incrementAndGet()
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, group)
        )
        two.start()
        try {
            // Give the second consumer time to join the group and poll. It must find nothing,
            // because the first consumer committed the offset after the store.
            delay(15000)
            assertEquals(0, second.get(), "the committed offset must not be read again")
        } finally {
            two.stop()
        }
    }

    @Test
    fun `a record that is not JSON never blocks the partition`() = runBlocking {
        val topic = "orders-${UUID.randomUUID()}"
        send(topic, "bad", "this is not json")
        send(topic, "good", """{"id":"evt-after-bad","type":"order.created"}""")

        val dead = ConcurrentHashMap<String, InboxMessage>()
        val stored = ConcurrentHashMap<String, InboxMessage>()
        val consumer = KafkaInboxConsumer(
            storeMessage = { message ->
                stored[message.idempotencyKey] = message
                InboxResult.Stored
            },
            extractor = IdempotencyExtractor(),
            config = consumerConfig(topic, "group-${UUID.randomUUID()}"),
            storeDeadMessage = { message ->
                dead[message.idempotencyKey] = message
                InboxResult.Stored
            }
        )

        try {
            consumer.start()
            withTimeout(60000) { while (!stored.containsKey("evt-after-bad")) delay(100) }
            assertEquals(1, dead.size, "the unreadable record must be stored dead")
            assertTrue(dead.keys.single().startsWith("sha256:"), "its key must be a digest of the body")
        } finally {
            consumer.stop()
        }
    }
}
