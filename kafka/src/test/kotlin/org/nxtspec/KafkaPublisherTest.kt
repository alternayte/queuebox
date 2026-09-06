package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaPublisherTest {

    private val kafka = KafkaContainer("apache/kafka:3.8.0")

    @BeforeAll
    fun start() {
        kafka.start()
    }

    @AfterAll
    fun stop() {
        kafka.stop()
    }

    private fun destination(topic: String) = Destination.Kafka(
        name = "events",
        bootstrapServers = kafka.bootstrapServers,
        topic = topic,
        timeoutMs = 30000
    )

    private fun readOne(topic: String): Pair<String?, String>? {
        val properties = Properties().apply {
            setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            setProperty(ConsumerConfig.GROUP_ID_CONFIG, "reader-${UUID.randomUUID()}")
            setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        KafkaConsumer<String, ByteArray>(properties).use { consumer ->
            consumer.subscribe(listOf(topic))
            repeat(30) {
                val records = consumer.poll(Duration.ofMillis(1000))
                val record = records.firstOrNull()
                if (record != null) {
                    lastHeaders = record.headers().associate { it.key() to it.value().decodeToString() }
                    return record.key() to record.value().decodeToString()
                }
            }
        }
        return null
    }

    private var lastHeaders: Map<String, String> = emptyMap()

    @Test
    fun `a published message arrives with its payload, key and identity headers`() = runBlocking {
        val topic = "out-${UUID.randomUUID()}"
        val publisher = KafkaPublisher()
        val message = OutboxMessage(
            topic = "order.created",
            key = "order-9",
            payload = JsonObject(mapOf("amount" to JsonPrimitive(10))),
            headers = mapOf("x-source" to "test")
        )

        try {
            val result = publisher.publish(message, destination(topic), PublishContext())
            assertTrue(result.isSuccess, "the publish must succeed: ${result.exceptionOrNull()}")

            val record = assertNotNull(readOne(topic), "the broker must hold the record")
            assertEquals("order-9", record.first, "the default key template renders the outbox key")
            assertEquals("""{"amount":10}""", record.second)
            assertEquals(message.id.toString(), lastHeaders["x-message-id"])
            assertEquals("order.created", lastHeaders["x-topic"])
            assertEquals("test", lastHeaders["x-source"])
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `the route key wins over the destination template`() = runBlocking {
        val topic = "out-${UUID.randomUUID()}"
        val publisher = KafkaPublisher()
        val message = OutboxMessage(topic = "order.created", key = "from-row", payload = JsonObject(emptyMap()))

        try {
            val result = publisher.publish(message, destination(topic), PublishContext(routingKey = "from-route"))
            assertTrue(result.isSuccess)
            assertEquals("from-route", assertNotNull(readOne(topic)).first)
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `an unreachable broker fails the publish without leaking the configuration`() = runBlocking {
        val publisher = KafkaPublisher()
        val dest = Destination.Kafka(
            name = "events",
            bootstrapServers = "localhost:1",
            topic = "nowhere",
            timeoutMs = 2000
        )

        try {
            val result = publisher.publish(
                OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())),
                dest,
                PublishContext()
            )
            assertTrue(result.isFailure, "an unreachable broker must fail the publish")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("events"), "the failure must name the destination: $message")
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `a message for another destination type is refused`() = runBlocking {
        val publisher = KafkaPublisher()
        try {
            assertTrue(publisher.supports(destination("any")))
            assertTrue(!publisher.supports(Destination.Http(name = "http", baseUrl = "https://example.com")))
        } finally {
            publisher.close()
        }
    }
}
