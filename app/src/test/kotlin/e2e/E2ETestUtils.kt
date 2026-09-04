package org.nxtspec.e2e

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import org.nxtspec.DatabaseConfig
import org.nxtspec.DestinationConfig
import org.nxtspec.InboxConfig
import org.nxtspec.OutboxConfig
import org.nxtspec.QueueBoxConfig
import org.nxtspec.RouteConfig
import org.nxtspec.Secret
import org.nxtspec.ServerConfig
import org.nxtspec.SourceConfig
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Utility object for creating E2E test configurations and helpers.
 */
object E2ETestUtils {

    /**
     * Create a QueueBoxConfig for E2E testing with the given container URLs.
     *
     * @param postgres PostgreSQL container for database connection
     * @param rabbitUrl AMQP URL for RabbitMQ (null if not using RabbitMQ)
     * @param httpUrl Base URL for HTTP destination (null if not using HTTP)
     * @param routes List of route configurations
     * @param sources Map of source configurations for inbox
     */
    fun createTestConfig(
        postgres: PostgreSQLContainer<*>,
        rabbitUrl: String? = null,
        httpUrl: String? = null,
        routes: List<RouteConfig> = emptyList(),
        sources: Map<String, SourceConfig> = emptyMap(),
        outboxConfig: OutboxConfig = OutboxConfig(
            pollIntervalMs = 50, // Fast polling for tests
            batchSize = 10,
            retryBaseDelayMs = 100, // Short delays for tests
            maxAttempts = 3
        )
    ): QueueBoxConfig {
        val destinations = mutableMapOf<String, DestinationConfig>()

        // Add HTTP destination if provided
        if (httpUrl != null) {
            destinations["http-destination"] = DestinationConfig.Http(
                baseUrl = httpUrl,
                path = "/webhook",
                timeoutMs = 5000
            )
        }

        // Add RabbitMQ destination if provided
        if (rabbitUrl != null) {
            destinations["rabbitmq-destination"] = DestinationConfig.RabbitMQ(
                url = rabbitUrl,
                exchange = "e2e-exchange",
                exchangeType = "topic"
            )
        }

        return QueueBoxConfig(
            server = ServerConfig(httpPort = 0), // Dynamic port for tests
            database = DatabaseConfig(
                url = postgres.jdbcUrl,
                username = postgres.username,
                password = Secret(postgres.password),
                poolSize = 5
            ),
            outbox = outboxConfig,
            inbox = InboxConfig(basePath = "/inbox"),
            destinations = destinations,
            routes = routes,
            sources = sources
        )
    }

    /**
     * Create a simple HTTP route configuration for testing.
     */
    fun httpRoute(topicPattern: String = ".*", destinationName: String = "http-destination"): RouteConfig = RouteConfig(
        topicPattern = topicPattern,
        destination = destinationName
    )

    /**
     * Create a RabbitMQ route configuration for testing.
     */
    fun rabbitMQRoute(
        topicPattern: String = ".*",
        destinationName: String = "rabbitmq-destination",
        routingKeyTemplate: String = "{{ topic }}"
    ): RouteConfig = RouteConfig(
        topicPattern = topicPattern,
        destination = destinationName,
        routingKeyTemplate = routingKeyTemplate
    )

    /**
     * Create an HTTP source configuration for inbox.
     */
    fun httpSource(
        path: String,
        idempotencyKeyPath: String = "$.id",
        eventTypePath: String? = null
    ): SourceConfig.Http = SourceConfig.Http(
        path = path,
        idempotencyKeyPath = idempotencyKeyPath,
        eventTypePath = eventTypePath
    )

    /**
     * Create a RabbitMQ source configuration for inbox.
     */
    fun rabbitMQSource(
        queueName: String,
        connectionUrl: String,
        idempotencyKeyPath: String = "$.id",
        prefetchCount: Int = 10
    ): SourceConfig.RabbitMQ = SourceConfig.RabbitMQ(
        queueName = queueName,
        connectionUrl = connectionUrl,
        idempotencyKeyPath = idempotencyKeyPath,
        prefetchCount = prefetchCount
    )
}

/**
 * Helper class for consuming RabbitMQ messages in E2E tests.
 * Tracks all received messages for verification.
 */
class RabbitMQTestConsumer(private val amqpUrl: String, private val queueName: String) {
    private val factory = ConnectionFactory().apply { setUri(amqpUrl) }
    private var connection: com.rabbitmq.client.Connection? = null
    private var channel: com.rabbitmq.client.Channel? = null

    private val _receivedMessages = CopyOnWriteArrayList<ReceivedMessage>()

    val receivedMessages: List<ReceivedMessage>
        get() = _receivedMessages.toList()

    /**
     * Start consuming messages from the queue.
     * Will declare the queue if it doesn't exist.
     */
    fun start(exchange: String? = null, routingKey: String = "#") {
        connection = factory.newConnection()
        channel = connection?.createChannel()

        channel?.let { ch ->
            // Declare queue
            ch.queueDeclare(queueName, false, false, true, null)

            // Bind to exchange if specified
            if (exchange != null) {
                ch.exchangeDeclare(exchange, "topic", false, false, true, null)
                ch.queueBind(queueName, exchange, routingKey)
            }

            // Start consuming
            ch.basicConsume(
                queueName,
                true,
                object : DefaultConsumer(ch) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        _receivedMessages.add(
                            ReceivedMessage(
                                body = String(body),
                                routingKey = envelope.routingKey,
                                headers = properties.headers?.mapValues { it.value?.toString() ?: "" } ?: emptyMap(),
                                messageId = properties.messageId
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Stop consuming and close connection.
     */
    fun stop() {
        channel?.close()
        connection?.close()
        channel = null
        connection = null
    }

    /**
     * Clear received messages.
     */
    fun clear() {
        _receivedMessages.clear()
    }

    /**
     * Wait for a specific number of messages to be received.
     * Returns true if the expected count is reached within timeout.
     */
    suspend fun waitForMessages(expectedCount: Int, timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (_receivedMessages.size < expectedCount) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false
            }
            kotlinx.coroutines.delay(50)
        }
        return true
    }

    data class ReceivedMessage(
        val body: String,
        val routingKey: String,
        val headers: Map<String, String>,
        val messageId: String?
    )
}

/**
 * Helper class for publishing test messages to RabbitMQ.
 */
class RabbitMQTestPublisher(private val amqpUrl: String) {
    private val factory = ConnectionFactory().apply { setUri(amqpUrl) }

    /**
     * Publish a message to a queue.
     */
    fun publishToQueue(
        queueName: String,
        payload: String,
        headers: Map<String, Any>? = null,
        messageId: String? = null,
        // Match consumer settings
        autoDelete: Boolean = true
    ) {
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                // Declare queue if it doesn't exist (match consumer settings)
                channel.queueDeclare(queueName, false, false, autoDelete, null)

                val props = AMQP.BasicProperties.Builder()
                    .headers(headers)
                    .messageId(messageId)
                    .build()

                channel.basicPublish("", queueName, props, payload.toByteArray())
            }
        }
    }

    /**
     * Publish a message to an exchange with a routing key.
     */
    fun publishToExchange(
        exchange: String,
        routingKey: String,
        payload: String,
        headers: Map<String, Any>? = null,
        messageId: String? = null
    ) {
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                // Declare exchange if it doesn't exist
                channel.exchangeDeclare(exchange, "topic", false, false, true, null)

                val props = AMQP.BasicProperties.Builder()
                    .headers(headers)
                    .messageId(messageId)
                    .build()

                channel.basicPublish(exchange, routingKey, props, payload.toByteArray())
            }
        }
    }
}
