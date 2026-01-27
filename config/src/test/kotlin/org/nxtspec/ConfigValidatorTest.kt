package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConfigValidatorTest {

    private fun createValidConfig(): QueueBoxConfig = QueueBoxConfig(
        server = ServerConfig(httpPort = 8080),
        database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/queuebox",
            username = "postgres",
            password = "secret",
            poolSize = 10,
            connectionTimeoutMs = 30000
        ),
        outbox = OutboxConfig(
            pollIntervalMs = 100,
            batchSize = 100,
            retryBaseDelayMs = 1000,
            maxAttempts = 5
        ),
        inbox = InboxConfig(basePath = "/inbox"),
        destinations = mapOf(
            "webhook-api" to DestinationConfig.Http(
                baseUrl = "https://api.example.com",
                path = "/webhooks",
                timeoutMs = 30000
            ),
            "events-exchange" to DestinationConfig.RabbitMQ(
                url = "amqp://localhost:5672",
                exchange = "events",
                exchangeType = "topic"
            )
        ),
        routes = listOf(
            RouteConfig(topicPattern = "order.*", destination = "webhook-api"),
            RouteConfig(topicPattern = "user.*", destination = "events-exchange")
        ),
        sources = emptyMap()
    )

    // === Valid Configuration Tests ===

    @Test
    fun `should pass when valid configuration`() {
        val config = createValidConfig()
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when minimum valid values`() {
        val config = QueueBoxConfig(
            server = ServerConfig(httpPort = 1),
            database = DatabaseConfig(
                url = "jdbc:postgresql://localhost:5432/db",
                username = "user",
                password = "pass",
                poolSize = 1,
                connectionTimeoutMs = 1
            ),
            outbox = OutboxConfig(
                pollIntervalMs = 1,
                batchSize = 1,
                maxAttempts = 1
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when maximum valid port`() {
        val config = createValidConfig().copy(
            server = ServerConfig(httpPort = 65535)
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    // === Database URL Validation ===

    @Test
    fun `should fail when invalid database url - mysql`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(url = "mysql://localhost:3306/db")
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "PostgreSQL")
    }

    @Test
    fun `should fail when invalid database url - empty`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(url = "")
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "PostgreSQL")
    }

    @Test
    fun `should fail when invalid database url - missing jdbc prefix`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(url = "postgresql://localhost:5432/db")
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "PostgreSQL")
    }

    // === Port Validation ===

    @Test
    fun `should fail when invalid port too high`() {
        val config = createValidConfig().copy(
            server = ServerConfig(httpPort = 70000)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "port")
    }

    @Test
    fun `should fail when invalid port too low`() {
        val config = createValidConfig().copy(
            server = ServerConfig(httpPort = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "port")
    }

    @Test
    fun `should fail when invalid port negative`() {
        val config = createValidConfig().copy(
            server = ServerConfig(httpPort = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "port")
    }

    // === Pool Size Validation ===

    @Test
    fun `should fail when invalid pool size zero`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(poolSize = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "pool size")
    }

    @Test
    fun `should fail when invalid pool size negative`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(poolSize = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "pool size")
    }

    // === Connection Timeout Validation ===

    @Test
    fun `should fail when invalid connection timeout zero`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(connectionTimeoutMs = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "connection timeout")
    }

    @Test
    fun `should fail when invalid connection timeout negative`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(connectionTimeoutMs = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "connection timeout")
    }

    // === Outbox Poll Interval Validation ===

    @Test
    fun `should fail when invalid poll interval zero`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(pollIntervalMs = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "poll interval")
    }

    @Test
    fun `should fail when invalid poll interval negative`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(pollIntervalMs = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "poll interval")
    }

    // === Outbox Batch Size Validation ===

    @Test
    fun `should fail when invalid batch size zero`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(batchSize = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "batch size")
    }

    @Test
    fun `should fail when invalid batch size negative`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(batchSize = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "batch size")
    }

    // === Outbox Max Attempts Validation ===

    @Test
    fun `should fail when invalid max attempts zero`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(maxAttempts = 0)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "max attempts")
    }

    @Test
    fun `should fail when invalid max attempts negative`() {
        val config = createValidConfig().copy(
            outbox = createValidConfig().outbox.copy(maxAttempts = -1)
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "max attempts")
    }

    // === Route-Destination Reference Validation ===

    @Test
    fun `should fail when route references nonexistent destination`() {
        val config = createValidConfig().copy(
            routes = listOf(RouteConfig(topicPattern = "*", destination = "nonexistent"))
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "nonexistent")
        assertContains(exception.message!!, "Available destinations")
    }

    @Test
    fun `should pass when route references existing destination`() {
        val config = createValidConfig().copy(
            routes = listOf(RouteConfig(topicPattern = "*", destination = "webhook-api"))
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when no routes configured`() {
        val config = createValidConfig().copy(routes = emptyList())
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when multiple routes reference invalid destinations`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(topicPattern = "order.*", destination = "webhook-api"),
                RouteConfig(topicPattern = "user.*", destination = "invalid-destination")
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "invalid-destination")
    }

    @Test
    fun `should pass when empty destinations and empty routes`() {
        val config = createValidConfig().copy(
            destinations = emptyMap(),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when route references destination with empty destinations map`() {
        val config = createValidConfig().copy(
            destinations = emptyMap(),
            routes = listOf(RouteConfig(topicPattern = "*", destination = "any"))
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "any")
    }
}
