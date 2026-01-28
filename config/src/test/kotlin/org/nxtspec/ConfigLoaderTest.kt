package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConfigLoaderTest {

    @Test
    fun `should load valid config when yaml is correct`() {
        val config = ConfigLoader.load("test-config.yml")

        assertNotNull(config)
        assertEquals(8080, config.server.httpPort)
        assertEquals("jdbc:postgresql://localhost:5432/queuebox", config.database.url)
        assertEquals("postgres", config.database.username)
        assertEquals("secret", config.database.password)
        assertEquals(10, config.database.poolSize)
        assertEquals(30000, config.database.connectionTimeoutMs)
        assertEquals(100, config.outbox.pollIntervalMs)
        assertEquals(100, config.outbox.batchSize)
        assertEquals(5, config.outbox.maxAttempts)
        assertEquals("/inbox", config.inbox.basePath)
        assertEquals(2, config.destinations.size)
        assertEquals(2, config.routes.size)
        assertEquals(2, config.sources.size)
    }

    @Test
    fun `should throw exception when required field missing`() {
        assertFailsWith<ConfigException> {
            ConfigLoader.load("missing-database.yml")
        }
    }

    @Test
    fun `should throw exception when file not found`() {
        assertFailsWith<ConfigException> {
            ConfigLoader.load("nonexistent-config.yml")
        }
    }

    @Test
    fun `should throw exception when database url is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load("invalid-url.yml")
        }
    }

    @Test
    fun `should throw exception when port is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load("invalid-port.yml")
        }
    }

    @Test
    fun `should load destinations correctly`() {
        val config = ConfigLoader.load("test-config.yml")

        val httpDestination = config.destinations["webhook-api"]
        assertNotNull(httpDestination)
        assert(httpDestination is DestinationConfig.Http)
        val http = httpDestination as DestinationConfig.Http
        assertEquals("https://api.example.com", http.baseUrl)
        assertEquals("/webhooks", http.path)
        assertEquals(30000, http.timeoutMs)
        assertEquals("Bearer token123", http.headers["Authorization"])

        val rabbitDestination = config.destinations["events-exchange"]
        assertNotNull(rabbitDestination)
        assert(rabbitDestination is DestinationConfig.RabbitMQ)
        val rabbit = rabbitDestination as DestinationConfig.RabbitMQ
        assertEquals("amqp://localhost:5672", rabbit.url)
        assertEquals("events", rabbit.exchange)
        assertEquals("topic", rabbit.exchangeType)
    }

    @Test
    fun `should load routes correctly`() {
        val config = ConfigLoader.load("test-config.yml")

        assertEquals(2, config.routes.size)
        val orderRoute = config.routes[0]
        assertEquals("order.*", orderRoute.topicPattern)
        assertEquals("webhook-api", orderRoute.destination)

        val userRoute = config.routes[1]
        assertEquals("user.*", userRoute.topicPattern)
        assertEquals("events-exchange", userRoute.destination)
        assertEquals("user.{{eventType}}", userRoute.routingKeyTemplate)
    }

    @Test
    fun `should load sources correctly`() {
        val config = ConfigLoader.load("test-config.yml")

        assertEquals(2, config.sources.size)

        val httpSource = config.sources["http-source"]
        assertNotNull(httpSource)
        assert(httpSource is SourceConfig.Http)
        val http = httpSource as SourceConfig.Http
        assertEquals("/events", http.path)
        assertEquals("$.headers.X-Idempotency-Key", http.idempotencyKeyPath)
        assertEquals("$.body.type", http.eventTypePath)

        val rabbitSource = config.sources["rabbit-source"]
        assertNotNull(rabbitSource)
        assert(rabbitSource is SourceConfig.RabbitMQ)
        val rabbit = rabbitSource as SourceConfig.RabbitMQ
        assertEquals("incoming-events", rabbit.queueName)
        assertEquals("amqp://localhost:5672", rabbit.connectionUrl)
        assertEquals("$.id", rabbit.idempotencyKeyPath)
        assertEquals(10, rabbit.prefetchCount)
    }

    @Test
    fun `should load retention config correctly`() {
        val config = ConfigLoader.load("test-config.yml")

        assert(config.retention.enabled)

        // Verify outbox retention config
        val outbox = config.retention.outbox
        assertEquals(RetentionPolicy.AGE, outbox.policy)
        assertEquals("7d", outbox.maxAge)
        assertEquals("1h", outbox.cleanupInterval)
        assertEquals(1000, outbox.batchSize)

        // Verify inbox retention config
        val inbox = config.retention.inbox
        assertEquals(RetentionPolicy.COUNT, inbox.policy)
        assertEquals(100000, inbox.maxCount)
        assertEquals("6h", inbox.cleanupInterval)
        assertEquals(1000, inbox.batchSize)
    }

}
