package org.nxtspec

import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers F-004. The routing key that the router resolves must reach RabbitMQ.
 */
@Tag("integration")
@Testcontainers
class RabbitPublisherRoutingKeyTest {

    companion object {
        private const val RABBITMQ_PORT = 5672
    }

    @Container
    private val rabbitMQContainer = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
        .withExposedPorts(RABBITMQ_PORT)
        .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
        .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

    private val amqpUrl: String
        get() = "amqp://guest:guest@${rabbitMQContainer.host}:${rabbitMQContainer.getMappedPort(RABBITMQ_PORT)}"

    private lateinit var publisher: RabbitPublisher

    @BeforeEach
    fun setup() {
        publisher = RabbitPublisher()
    }

    @AfterEach
    fun teardown() {
        runBlocking { publisher.close() }
    }

    @Test
    fun `publishes with the routing key the router supplies`() = runBlocking {
        val exchange = "route-key-exchange"
        val queue = "route-key-queue"
        val boundKey = "eu.high.order.created"

        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare(exchange, "topic", true)
                channel.queueDeclare(queue, true, false, false, null)
                channel.queueBind(queue, exchange, boundKey)
            }
        }

        val destination = Destination.RabbitMQ(
            name = "route-key-test",
            url = amqpUrl,
            exchange = exchange,
            exchangeType = "topic",
            // The destination template must not win over the route routing key.
            routingKeyTemplate = "{{ topic }}"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "order.created",
            payload = JsonObject(
                mapOf(
                    "region" to JsonPrimitive("eu"),
                    "priority" to JsonPrimitive("high")
                )
            )
        )

        // MessageRouter renders the route template
        // "{{ payload.region }}.{{ payload.priority }}.{{ topic }}" to this value.
        // RoutingKeyRendererTest covers the rendering itself.
        val routingKey = "eu.high.order.created"

        val result = publisher.publish(message, destination, PublishContext(routingKey = routingKey))

        assertTrue(result.isSuccess, "Publish must succeed: ${result.exceptionOrNull()?.message}")

        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                val response = channel.basicGet(queue, true)
                assertNotNull(response, "The message must arrive on the bound queue")
                assertEquals(boundKey, response.envelope.routingKey)
            }
        }
    }

    @Test
    fun `falls back to the destination template when the route sets no routing key`() = runBlocking {
        val exchange = "fallback-exchange"
        val queue = "fallback-queue"

        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare(exchange, "topic", true)
                channel.queueDeclare(queue, true, false, false, null)
                channel.queueBind(queue, exchange, "events.user.signup.v1")
            }
        }

        val destination = Destination.RabbitMQ(
            name = "fallback-test",
            url = amqpUrl,
            exchange = exchange,
            exchangeType = "topic",
            routingKeyTemplate = "events.{{ topic }}.v1"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "user.signup",
            payload = JsonObject(mapOf("userId" to JsonPrimitive("user-123")))
        )

        val result = publisher.publish(message, destination, PublishContext(routingKey = null))

        assertTrue(result.isSuccess)

        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                val response = channel.basicGet(queue, true)
                assertNotNull(response, "The destination template must apply as the fallback")
                assertEquals("events.user.signup.v1", response.envelope.routingKey)
            }
        }
    }
}
