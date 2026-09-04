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
 * Integration tests requiring a running RabbitMQ container.
 * Uses tmpfs mount to work around macOS Docker cookie file permission issues.
 */
@Tag("integration")
@Testcontainers
class RabbitPublisherIntegrationTest {

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
        runBlocking {
            publisher.close()
        }
    }

    /**
     * Declare the exchange and bind a queue. F-022 makes an unroutable publish fail, so a
     * test that expects success must give the message a route.
     */
    private fun bindQueue(exchange: String, exchangeType: String, routingKey: String) {
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.exchangeDeclare(exchange, exchangeType, true)
                val queue = "bind-${UUID.randomUUID()}"
                channel.queueDeclare(queue, false, false, false, null)
                channel.queueBind(queue, exchange, routingKey)
            }
        }
    }

    @Test
    fun `publish message successfully with confirms`() = runBlocking {
        val destination = Destination.RabbitMQ(
            name = "integration-test",
            url = amqpUrl,
            exchange = "test-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )

        bindQueue("test-exchange", "topic", "orders.created")

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "orders.created",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("12345")))
        )

        val result = publisher.publish(message, destination)

        assertTrue(result.isSuccess, "Publish should succeed: ${result.exceptionOrNull()?.message}")
    }

    @Test
    fun `routing key template replaces topic correctly`() = runBlocking {
        val destination = Destination.RabbitMQ(
            name = "routing-test",
            url = amqpUrl,
            exchange = "routing-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "events.{{ topic }}.v1"
        )

        bindQueue("routing-exchange", "topic", "events.user.signup.v1")

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "user.signup",
            payload = JsonObject(mapOf("userId" to JsonPrimitive("user-123")))
        )

        val result = publisher.publish(message, destination)

        assertTrue(result.isSuccess, "Publish should succeed: ${result.exceptionOrNull()?.message}")
    }

    @Test
    fun `connection is reused for same destination`() = runBlocking {
        val destination = Destination.RabbitMQ(
            name = "reuse-test",
            url = amqpUrl,
            exchange = "reuse-exchange",
            exchangeType = "topic"
        )

        val message1 = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "test1",
            payload = JsonObject(mapOf("msg" to JsonPrimitive("first")))
        )

        bindQueue("reuse-exchange", "topic", "#")

        val message2 = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "test2",
            payload = JsonObject(mapOf("msg" to JsonPrimitive("second")))
        )

        val first = publisher.publish(message1, destination)
        val second = publisher.publish(message2, destination)

        assertTrue(first.isSuccess, "The first publish must succeed")
        assertTrue(second.isSuccess, "The second publish must reuse the cached channel")
    }

    @Test
    fun `publish with direct exchange type`() = runBlocking {
        val destination = Destination.RabbitMQ(
            name = "direct-test",
            url = amqpUrl,
            exchange = "direct-exchange",
            exchangeType = "direct",
            routingKeyTemplate = "{{topic}}"
        )

        bindQueue("direct-exchange", "direct", "direct-key")

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "direct-key",
            payload = JsonObject(mapOf("data" to JsonPrimitive("test")))
        )

        val result = publisher.publish(message, destination)

        assertTrue(result.isSuccess, "Direct exchange publish should succeed")
    }

    @Test
    fun `should includeMessageHeaders when publishing`(): Unit = runBlocking {
        val exchangeName = "headers-test-exchange"
        val queueName = "headers-test-queue"
        val routingKey = "test.topic"

        // Set up exchange, queue, and binding to capture the published message
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.exchangeDeclare(exchangeName, "topic", true)
                channel.queueDeclare(queueName, false, false, true, null)
                channel.queueBind(queueName, exchangeName, routingKey)
            }
        }

        // Publish message
        val destination = Destination.RabbitMQ(
            name = "headers-test",
            url = amqpUrl,
            exchange = exchangeName,
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = routingKey,
            payload = JsonObject(mapOf("data" to JsonPrimitive("test")))
        )

        val result = publisher.publish(message, destination)
        assertTrue(result.isSuccess, "Publish should succeed")

        // Consume and verify headers
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                val response = channel.basicGet(queueName, true)
                assertNotNull(response, "Should receive the published message")

                val headers = response.props.headers
                assertNotNull(headers, "Message should have headers")
                assertEquals(routingKey, headers["x-topic"]?.toString(), "x-topic header should contain the message topic")
                assertNotNull(headers["x-attempt"], "x-attempt header should be present")
            }
        }
    }

    @Test
    fun `should autoCreateExchange when exchangeDoesNotExist`() = runBlocking {
        // Use a unique exchange name that doesn't exist
        val uniqueExchange = "auto-create-exchange-${UUID.randomUUID()}"

        val destination = Destination.RabbitMQ(
            name = "auto-create-test",
            url = amqpUrl,
            exchange = uniqueExchange,
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "test.topic",
            payload = JsonObject(mapOf("data" to JsonPrimitive("test")))
        )

        // The publisher declares the exchange when it opens the channel. F-022 makes this
        // first publish fail, because the new exchange has no binding yet.
        val firstResult = publisher.publish(message, destination)
        assertTrue(firstResult.isFailure, "An unroutable publish must fail")

        // Verify the exchange was actually created
        val factory = ConnectionFactory().apply { setUri(amqpUrl) }
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                // exchangeDeclarePassive will throw if exchange doesn't exist
                channel.exchangeDeclarePassive(uniqueExchange)
                // If we get here without exception, the exchange exists
                
            }
        }

        // Bind a queue, then publish again. The auto-declared exchange now routes the message.
        bindQueue(uniqueExchange, "topic", "test.topic")
        val secondResult = publisher.publish(
            message.copy(id = UUID.randomUUID()),
            destination
        )
        assertTrue(
            secondResult.isSuccess,
            "Publish should succeed: ${secondResult.exceptionOrNull()?.message}"
        )
    }
}
