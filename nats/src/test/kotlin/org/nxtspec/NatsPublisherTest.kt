package org.nxtspec

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NatsPublisherTest {

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

    private fun createStream(subjectPrefix: String): String {
        val stream = "out${UUID.randomUUID().toString().replace("-", "")}"
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

    @Test
    fun `a published message arrives with its payload and identity headers`() = runBlocking {
        val prefix = "out${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        val publisher = NatsPublisher()
        val message = OutboxMessage(
            topic = "order.created",
            key = "order-9",
            payload = JsonObject(mapOf("amount" to JsonPrimitive(10))),
            headers = mapOf("x-source" to "test")
        )

        try {
            val destination = Destination.Nats(
                name = "events",
                servers = servers,
                subject = "$prefix.created"
            )
            val result = publisher.publish(
                message,
                destination,
                PublishContext(resolvedAddress = destination.subject)
            )
            assertTrue(result.isSuccess, "the publish must succeed: ${result.exceptionOrNull()}")

            connect(servers, null, null, null, 10000).use { connection ->
                val subscription = connection.jetStream().subscribe("$prefix.>")
                val received = assertNotNull(
                    subscription.nextMessage(Duration.ofSeconds(20)),
                    "the stream must hold the message"
                )
                assertEquals("""{"amount":10}""", received.data.decodeToString())
                assertEquals(message.id.toString(), received.headers.getFirst("x-message-id"))
                assertEquals("order.created", received.headers.getFirst("x-topic"))
                assertEquals("order-9", received.headers.getFirst("x-message-key"))
                assertEquals("test", received.headers.getFirst("x-source"))
                received.ack()
            }
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `the resolved address wins over the configured subject`() = runBlocking {
        val prefix = "out${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        val publisher = NatsPublisher()

        try {
            val destination = Destination.Nats(
                name = "events",
                servers = servers,
                subject = "$prefix.default"
            )
            val result = publisher.publish(
                OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())),
                destination,
                PublishContext(resolvedAddress = "$prefix.routed")
            )
            assertTrue(result.isSuccess, "the publish must succeed: ${result.exceptionOrNull()}")

            connect(servers, null, null, null, 10000).use { connection ->
                val subscription = connection.jetStream().subscribe("$prefix.routed")
                val received = assertNotNull(subscription.nextMessage(Duration.ofSeconds(20)))
                assertEquals("$prefix.routed", received.subject)
                received.ack()
            }
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `two aggregate types reach two subjects through one destination`() = runBlocking {
        val prefix = "out${UUID.randomUUID().toString().take(8)}"
        val stream = createStream(prefix)
        val publisher = NatsPublisher()

        try {
            val destination = Destination.Nats(
                name = "events",
                servers = servers,
                subject = "$prefix.{{ aggregateType }}"
            )
            val taskResult = publisher.publish(
                OutboxMessage(topic = "order.created", aggregateType = "Task", payload = JsonObject(emptyMap())),
                destination,
                PublishContext(resolvedAddress = "$prefix.Task")
            )
            val orderResult = publisher.publish(
                OutboxMessage(topic = "order.created", aggregateType = "Order", payload = JsonObject(emptyMap())),
                destination,
                PublishContext(resolvedAddress = "$prefix.Order")
            )
            assertTrue(taskResult.isSuccess, "task publish should succeed: ${taskResult.exceptionOrNull()}")
            assertTrue(orderResult.isSuccess, "order publish should succeed: ${orderResult.exceptionOrNull()}")

            connect(servers, null, null, null, 10000).use { connection ->
                val taskSub = connection.jetStream().subscribe("$prefix.Task")
                assertNotNull(taskSub.nextMessage(Duration.ofSeconds(20)), "the Task subject must hold a message")
                val orderSub = connection.jetStream().subscribe("$prefix.Order")
                assertNotNull(orderSub.nextMessage(Duration.ofSeconds(20)), "the Order subject must hold a message")
                Unit
            }
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `a JetStream publish to a subject that no stream holds fails`() = runBlocking {
        val publisher = NatsPublisher()
        try {
            val destination = Destination.Nats(
                name = "events",
                servers = servers,
                subject = "nostream.${UUID.randomUUID()}",
                timeoutMs = 5000
            )
            val result = publisher.publish(
                OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())),
                destination,
                PublishContext(resolvedAddress = destination.subject)
            )
            // The outbox must not mark a row sent when no stream accepted the message.
            assertTrue(result.isFailure, "a subject that no stream holds must fail the publish")
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("events"))
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `a message for another destination type is refused`() = runBlocking {
        val publisher = NatsPublisher()
        try {
            assertTrue(publisher.supports(Destination.Nats(name = "n", servers = servers, subject = "s")))
            assertTrue(!publisher.supports(Destination.Http(name = "http", baseUrl = "https://example.com")))
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `an empty resolved address fails the row and publishes nothing`() = runBlocking {
        // A mocked connection accepts a publish to any subject, including an empty one. If the
        // publisher ever stopped checking the resolved address and let the call through, this
        // connection would happily record it, and the verify below would catch that.
        val jetStream = mockk<JetStream>()
        val connection = mockk<Connection>()
        every { connection.jetStream() } returns jetStream
        every { jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>()) } returns
            CompletableFuture.completedFuture(mockk<PublishAck>())
        val publisher = NatsPublisher(connectionFactory = { connection })
        val destination = Destination.Nats(
            name = "events",
            servers = servers,
            subject = "{{ aggregateType }}"
        )

        try {
            val result = publisher.publish(
                OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())),
                destination,
                PublishContext(resolvedAddress = "")
            )

            assertTrue(result.isFailure, "an empty resolved subject must fail the row")
            val message = result.exceptionOrNull()?.message
            assertNotNull(message, "the failure must carry a message")
            assertTrue(message.contains(destination.name), "the failure must name the destination")
            verify(exactly = 0) { jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>()) }
        } finally {
            publisher.close()
        }
    }
}
