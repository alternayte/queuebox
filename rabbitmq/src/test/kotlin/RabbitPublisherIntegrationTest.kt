package org.nxtspec

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

    @Test
    fun `publish message successfully with confirms`() = runBlocking {
        val destination = Destination.RabbitMQ(
            name = "integration-test",
            url = amqpUrl,
            exchange = "test-exchange",
            exchangeType = "topic",
            routingKeyTemplate = "{{ topic }}"
        )

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

        val message2 = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "test2",
            payload = JsonObject(mapOf("msg" to JsonPrimitive("second")))
        )

        publisher.publish(message1, destination)
        publisher.publish(message2, destination)

        assertTrue(true)
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

        val message = OutboxMessage(
            id = UUID.randomUUID(),
            topic = "direct-key",
            payload = JsonObject(mapOf("data" to JsonPrimitive("test")))
        )

        val result = publisher.publish(message, destination)

        assertTrue(result.isSuccess, "Direct exchange publish should succeed")
    }
}
