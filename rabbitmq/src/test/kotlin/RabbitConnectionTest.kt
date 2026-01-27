package org.nxtspec

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests for RabbitConnection lifecycle management.
 * Tests connection creation, channel acquisition, and auto-reconnection behavior.
 */
@Tag("integration")
@Testcontainers
class RabbitConnectionTest {

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

    private var connection: RabbitConnection? = null

    @AfterEach
    fun teardown() {
        runBlocking {
            connection?.close()
        }
    }

    @Test
    fun `should connectSuccessfully when validUrl`() = runBlocking {
        connection = RabbitConnection(amqpUrl)
        val channel = connection!!.getChannel()

        assertTrue(channel.isOpen, "Channel should be open after getChannel()")

        channel.close()
    }

    @Test
    fun `should reuseConnection when multipleChannelsRequested`() = runBlocking {
        connection = RabbitConnection(amqpUrl)
        val channel1 = connection!!.getChannel()
        val channel2 = connection!!.getChannel()

        assertTrue(channel1.isOpen, "First channel should be open")
        assertTrue(channel2.isOpen, "Second channel should be open")
        assertNotEquals(channel1.channelNumber, channel2.channelNumber, "Channels should have different channel numbers")

        channel1.close()
        channel2.close()
    }

    @Test
    fun `should reconnect when connectionClosed`() = runBlocking {
        connection = RabbitConnection(amqpUrl)
        val channel1 = connection!!.getChannel()
        assertTrue(channel1.isOpen, "Initial channel should be open")
        channel1.close()

        // Force close the connection - this sets internal connection to null
        connection!!.close()

        // getChannel() should create a new connection automatically
        // This tests the auto-recovery logic in RabbitConnection:21-30
        val channel2 = connection!!.getChannel()
        assertTrue(channel2.isOpen, "Channel should be open after reconnection")

        channel2.close()
    }
}
