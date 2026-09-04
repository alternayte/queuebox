package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ConfigValidatorTest {

    private fun createValidConfig(): QueueBoxConfig = QueueBoxConfig(
        server = ServerConfig(httpPort = 8080),
        database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/queuebox",
            username = "postgres",
            password = Secret("secret"),
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

    // === Third review gate, defect 1 and defect 2 ===

    @Test
    fun `should reject the count retention policy for the inbox`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
                inbox = TableRetentionConfig(policy = RetentionPolicy.COUNT, maxCount = 100000)
            )
        )
        val error = assertFailsWith<IllegalArgumentException> { ConfigValidator.validate(config) }
        assertContains(error.message!!, "inbox retention does not support the count policy")
    }

    @Test
    fun `should give the relay the configured dead-letter ceiling`() {
        val config = createValidConfig().copy(outbox = createValidConfig().outbox.copy(maxAttempts = 3))
        val validated = ConfigValidator.validate(config)
        assertEquals(3, validated.inbox.relay.maxAttempts)
    }

    @Test
    fun `should keep an explicit relay dead-letter ceiling`() {
        val base = createValidConfig()
        val config = base.copy(
            outbox = base.outbox.copy(maxAttempts = 3),
            inbox = base.inbox.copy(relay = base.inbox.relay.copy(maxAttempts = 9))
        )
        val validated = ConfigValidator.validate(config)
        assertEquals(9, validated.inbox.relay.maxAttempts)
    }

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
                password = Secret("pass"),
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
        assertContains(exception.message!!, "jdbc:postgresql://")
    }

    @Test
    fun `should fail when invalid database url - empty`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(url = "")
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "jdbc:postgresql://")
    }

    @Test
    fun `should fail when invalid database url - missing jdbc prefix`() {
        val config = createValidConfig().copy(
            database = createValidConfig().database.copy(url = "postgresql://localhost:5432/db")
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "jdbc:postgresql://")
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

    // === Topic Pattern Validation (F-026) ===

    @Test
    fun `should pass when topic pattern uses allowed characters`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(topicPattern = "order.*", destination = "webhook-api"),
                RouteConfig(topicPattern = "order_v2.**", destination = "webhook-api"),
                RouteConfig(topicPattern = "order-v2.created", destination = "webhook-api"),
                RouteConfig(topicPattern = "**", destination = "webhook-api")
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when topic pattern contains a regex metacharacter`() {
        // MessageRouter escapes every literal segment, so a metacharacter is a literal.
        // MessageRouterTest proves that such a pattern routes correctly, so the validator must
        // not reject it.
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(topicPattern = "order.(a+)", destination = "webhook-api"),
                RouteConfig(topicPattern = "order.[y]", destination = "webhook-api"),
                RouteConfig(topicPattern = "order created", destination = "webhook-api")
            )
        )

        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `should fail when topic pattern has more than two consecutive wildcards`() {
        val config = createValidConfig().copy(
            routes = listOf(RouteConfig(topicPattern = "order.***", destination = "webhook-api"))
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "topicPattern")
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

    // === Retention Configuration Validation ===

    @Test
    fun `should pass when retention is disabled`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(enabled = false)
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when retention is disabled with any table config`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = false,
                outbox = TableRetentionConfig(policy = RetentionPolicy.AGE),
                inbox = TableRetentionConfig(policy = RetentionPolicy.COUNT)
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when age policy has valid maxAge`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "7d",
                    cleanupInterval = "1h",
                    batchSize = 1000
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when age policy has no maxAge`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = null
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "outbox")
        assertContains(exception.message!!, "maxAge")
    }

    @Test
    fun `should fail when age policy has invalid maxAge format`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "invalid"
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Invalid duration format")
    }

    @Test
    fun `should pass when count policy has valid maxCount`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = 100000,
                    cleanupInterval = "6h",
                    batchSize = 1000
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when count policy has no maxCount`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = null
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "inbox")
        assertContains(exception.message!!, "maxCount")
    }

    @Test
    fun `should fail when count policy has zero maxCount`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = 0
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "inbox")
        assertContains(exception.message!!, "positive maxCount")
    }

    @Test
    fun `should fail when count policy has negative maxCount`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = -1
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "inbox")
        assertContains(exception.message!!, "positive maxCount")
    }

    @Test
    fun `should pass when disabled policy has no additional fields`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
                inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when invalid cleanupInterval format`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "7d",
                    cleanupInterval = "invalid"
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Invalid duration format")
    }

    @Test
    fun `should fail when batchSize is zero`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "7d",
                    batchSize = 0
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "outbox")
        assertContains(exception.message!!, "batchSize")
    }

    @Test
    fun `should fail when batchSize is negative`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = 1000,
                    batchSize = -1
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "inbox")
        assertContains(exception.message!!, "batchSize")
    }

    @Test
    fun `should validate both outbox and inbox independently`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "7d"
                ),
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = null
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "inbox")
        assertContains(exception.message!!, "maxCount")
    }

    @Test
    fun `should pass when both outbox and inbox have valid configs`() {
        val config = createValidConfig().copy(
            retention = RetentionConfig(
                enabled = true,
                outbox = TableRetentionConfig(
                    policy = RetentionPolicy.COUNT,
                    maxCount = 100000,
                    cleanupInterval = "1h",
                    batchSize = 1000
                ),
                inbox = TableRetentionConfig(
                    policy = RetentionPolicy.AGE,
                    maxAge = "30d",
                    cleanupInterval = "6h",
                    batchSize = 500
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    // === Transform Configuration Validation ===

    @Test
    fun `should pass when route has valid transform`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        timeoutMs = 100,
                        maxDepth = 50
                    )
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when route transform is null`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = null
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when route transform expression is blank`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "")
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "transform expression cannot be blank")
    }

    @Test
    fun `should fail when route transform expression is whitespace only`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "   ")
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "transform expression cannot be blank")
    }

    @Test
    fun `should fail when route transform timeoutMs is zero`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "{ \"id\": id }", timeoutMs = 0)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "timeoutMs must be positive")
    }

    @Test
    fun `should fail when route transform timeoutMs is negative`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "{ \"id\": id }", timeoutMs = -100)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "timeoutMs must be positive")
    }

    @Test
    fun `should fail when route transform maxDepth is zero`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "{ \"id\": id }", maxDepth = 0)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "maxDepth must be positive")
    }

    @Test
    fun `should fail when route transform maxDepth is negative`() {
        val config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "{ \"id\": id }", maxDepth = -10)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "maxDepth must be positive")
    }

    @Test
    fun `should pass when destination has valid transform`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    transform = TransformConfig(expression = "{ \"wrapped\": $ }")
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when destination transform expression is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    transform = TransformConfig(expression = "")
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Destination 'webhook-api'")
        assertContains(exception.message!!, "transform expression cannot be blank")
    }

    @Test
    fun `should fail when destination transform timeoutMs is zero`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    transform = TransformConfig(expression = "{ \"id\": id }", timeoutMs = 0)
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Destination 'webhook-api'")
        assertContains(exception.message!!, "timeoutMs must be positive")
    }

    @Test
    fun `should fail when destination transform maxDepth is zero`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    transform = TransformConfig(expression = "{ \"id\": id }", maxDepth = 0)
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Destination 'webhook-api'")
        assertContains(exception.message!!, "maxDepth must be positive")
    }

    @Test
    fun `should pass when both route and destination have transforms`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    transform = TransformConfig(expression = "{ \"wrapped\": $ }")
                )
            ),
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(expression = "{ \"orderId\": id }")
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    // === Source Transform Configuration Validation ===

    @Test
    fun `should pass when http source has valid transform`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(
                        expression = "{ \"eventId\": id }",
                        timeoutMs = 100,
                        maxDepth = 50
                    )
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when http source transform is null`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = null
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when rabbitmq source has valid transform`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    idempotencyKeyPath = "$.orderId",
                    transform = TransformConfig(
                        expression = """{ "orderId": orderId, "total": ${"$"}sum(items.price) }""",
                        timeoutMs = 200,
                        onError = TransformErrorStrategy.Skip
                    )
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when rabbitmq source transform is null`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    transform = null
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when http source transform expression is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(expression = "")
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'stripe-webhooks'")
        assertContains(exception.message!!, "transform expression cannot be blank")
    }

    @Test
    fun `should fail when rabbitmq source transform expression is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    transform = TransformConfig(expression = "   ")
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'order-events'")
        assertContains(exception.message!!, "transform expression cannot be blank")
    }

    @Test
    fun `should fail when source transform timeoutMs is zero`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(expression = "{ \"id\": id }", timeoutMs = 0)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'stripe-webhooks'")
        assertContains(exception.message!!, "timeoutMs must be positive")
    }

    @Test
    fun `should fail when source transform timeoutMs is negative`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(expression = "{ \"id\": id }", timeoutMs = -50)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'stripe-webhooks'")
        assertContains(exception.message!!, "timeoutMs must be positive")
    }

    @Test
    fun `should fail when source transform maxDepth is zero`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    transform = TransformConfig(expression = "{ \"id\": id }", maxDepth = 0)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'order-events'")
        assertContains(exception.message!!, "maxDepth must be positive")
    }

    @Test
    fun `should fail when source transform maxDepth is negative`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    transform = TransformConfig(expression = "{ \"id\": id }", maxDepth = -10)
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Source 'order-events'")
        assertContains(exception.message!!, "maxDepth must be positive")
    }

    @Test
    fun `should pass when source transform uses all error strategies`() {
        // Test FAIL (default)
        var config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Fail
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))

        // Test SKIP
        config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Skip
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))

        // Test DEAD
        config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Dead
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `should pass when multiple sources have transforms`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    transform = TransformConfig(expression = "{ \"eventId\": id }")
                ),
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    transform = TransformConfig(expression = "{ \"orderId\": orderId }")
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when transform uses all error strategies`() {
        // Test FAIL (default)
        var config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Fail
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))

        // Test SKIP
        config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Skip
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))

        // Test DEAD
        config = createValidConfig().copy(
            routes = listOf(
                RouteConfig(
                    topicPattern = "order.*",
                    destination = "webhook-api",
                    transform = TransformConfig(
                        expression = "{ \"id\": id }",
                        onError = TransformErrorStrategy.Dead
                    )
                )
            )
        )
        assertNotNull(ConfigValidator.validate(config))
    }

    // === Inbox Authentication Validation ===

    @Test
    fun `should pass when source has valid bearer auth`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.Bearer(token = Secret("secret-token"))
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when bearer token is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.Bearer(token = Secret(""))
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "bearer token cannot be blank")
    }

    @Test
    fun `should pass when source has valid api key auth`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "partner-webhook" to SourceConfig.Http(
                    path = "/webhooks/partner",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret("my-api-key"))
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when api key is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "partner-webhook" to SourceConfig.Http(
                    path = "/webhooks/partner",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret(""))
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "API key cannot be blank")
    }

    @Test
    fun `should fail when api key header name is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "partner-webhook" to SourceConfig.Http(
                    path = "/webhooks/partner",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.ApiKey(headerName = "", key = Secret("my-key"))
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "header name cannot be blank")
    }

    @Test
    fun `should pass when source has valid hmac auth`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("webhook-secret"),
                        headerName = "Stripe-Signature",
                        algorithm = "HmacSHA256"
                    )
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when hmac secret is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret(""),
                        headerName = "X-Signature",
                        algorithm = "HmacSHA256"
                    )
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "HMAC secret cannot be blank")
    }

    @Test
    fun `should fail when hmac algorithm is invalid`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("webhook-secret"),
                        headerName = "X-Signature",
                        algorithm = "MD5"
                    )
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "algorithm must be HmacSHA256 or HmacSHA512")
    }

    @Test
    fun `should pass when hmac uses SHA512 algorithm`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "secure-webhook" to SourceConfig.Http(
                    path = "/webhooks/secure",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("webhook-secret"),
                        headerName = "X-Signature",
                        algorithm = "HmacSHA512"
                    )
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when hmac header name is blank`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "secure-webhook" to SourceConfig.Http(
                    path = "/webhooks/secure",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("webhook-secret"),
                        headerName = "",
                        algorithm = "HmacSHA256"
                    )
                )
            )
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "signature header name cannot be blank")
    }

    @Test
    fun `should pass when source auth is null`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "public-webhook" to SourceConfig.Http(
                    path = "/webhooks/public",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = null
                )
            )
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    // === Destination Authentication Validation ===

    @Test
    fun `should pass when destination has valid oauth2 auth`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "oauth-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "client-id",
                        clientSecret = Secret("client-secret"),
                        tokenUrl = "https://auth.example.com/token"
                    )
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when oauth2 clientId is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "oauth-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "",
                        clientSecret = Secret("client-secret"),
                        tokenUrl = "https://auth.example.com/token"
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "OAuth2 clientId cannot be blank")
    }

    @Test
    fun `should fail when oauth2 clientSecret is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "oauth-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "client-id",
                        clientSecret = Secret(""),
                        tokenUrl = "https://auth.example.com/token"
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "OAuth2 clientSecret cannot be blank")
    }

    @Test
    fun `should fail when oauth2 tokenUrl is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "oauth-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/webhooks",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "client-id",
                        clientSecret = Secret("client-secret"),
                        tokenUrl = ""
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "tokenUrl cannot be blank")
    }

    @Test
    fun `should pass when destination has valid basic auth`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "legacy-api" to DestinationConfig.Http(
                    baseUrl = "https://legacy.example.com",
                    path = "/api",
                    auth = DestinationAuthConfig.Basic(
                        username = "user",
                        password = Secret("pass")
                    )
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when basic auth username is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "legacy-api" to DestinationConfig.Http(
                    baseUrl = "https://legacy.example.com",
                    path = "/api",
                    auth = DestinationAuthConfig.Basic(
                        username = "",
                        password = Secret("pass")
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "Basic auth username cannot be blank")
    }

    @Test
    fun `should pass when basic auth password is empty`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "legacy-api" to DestinationConfig.Http(
                    baseUrl = "https://legacy.example.com",
                    path = "/api",
                    auth = DestinationAuthConfig.Basic(
                        username = "user",
                        password = Secret("")
                    )
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should pass when destination has valid header auth`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "api-key-service" to DestinationConfig.Http(
                    baseUrl = "https://api.service.com",
                    path = "/v1",
                    auth = DestinationAuthConfig.Header(
                        headerName = "X-API-Key",
                        headerValue = Secret("my-api-key")
                    )
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should fail when header auth name is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "api-key-service" to DestinationConfig.Http(
                    baseUrl = "https://api.service.com",
                    path = "/v1",
                    auth = DestinationAuthConfig.Header(
                        headerName = "",
                        headerValue = Secret("my-api-key")
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "header name cannot be blank")
    }

    @Test
    fun `should fail when header auth value is blank`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "api-key-service" to DestinationConfig.Http(
                    baseUrl = "https://api.service.com",
                    path = "/v1",
                    auth = DestinationAuthConfig.Header(
                        headerName = "X-API-Key",
                        headerValue = Secret("")
                    )
                )
            ),
            routes = emptyList()
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
        assertContains(exception.message!!, "header value cannot be blank")
    }

    @Test
    fun `should pass when destination auth is null`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "public-api" to DestinationConfig.Http(
                    baseUrl = "https://public.api.com",
                    path = "/",
                    auth = null
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    @Test
    fun `should validate auth for multiple sources and destinations`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("stripe-secret"),
                        algorithm = "HmacSHA256"
                    )
                ),
                "github" to SourceConfig.Http(
                    path = "/github",
                    idempotencyKeyPath = "$.delivery",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("github-secret"),
                        algorithm = "HmacSHA256",
                        headerName = "X-Hub-Signature-256"
                    )
                )
            ),
            destinations = mapOf(
                "oauth-api" to DestinationConfig.Http(
                    baseUrl = "https://oauth.api.com",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "id",
                        clientSecret = Secret("secret"),
                        tokenUrl = "https://auth.com/token"
                    )
                ),
                "basic-api" to DestinationConfig.Http(
                    baseUrl = "https://basic.api.com",
                    auth = DestinationAuthConfig.Basic(
                        username = "user",
                        password = Secret("pass")
                    )
                )
            ),
            routes = emptyList()
        )
        val validated = ConfigValidator.validate(config)
        assertNotNull(validated)
    }

    // --- F-011: table name validation ---

    @Test
    fun `validate should reject an outbox table name that contains SQL`() {
        val config = createValidConfig().let {
            it.copy(database = it.database.copy(outboxTableName = "outbox; DROP TABLE users --"))
        }

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "database.outboxTableName")
        assertContains(exception.message!!, "QUEUEBOX_DATABASE_OUTBOXTABLENAME")
    }

    @Test
    fun `validate should reject an inbox table name that contains SQL`() {
        val config = createValidConfig().let {
            it.copy(database = it.database.copy(inboxTableName = "inbox; DROP TABLE users --"))
        }

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "database.inboxTableName")
        assertContains(exception.message!!, "QUEUEBOX_DATABASE_INBOXTABLENAME")
    }

    @Test
    fun `validate should reject a blank table name`() {
        val config = createValidConfig().let {
            it.copy(database = it.database.copy(outboxTableName = ""))
        }

        assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }
    }

    @Test
    fun `validate should accept a legitimate custom table name`() {
        val config = createValidConfig().let {
            it.copy(
                database = it.database.copy(
                    outboxTableName = "my_schema_outbox",
                    inboxTableName = "my_schema_inbox"
                )
            )
        }

        val result = ConfigValidator.validate(config)

        assertNotNull(result)
    }

    // --- F-002: source topic template ---

    @Test
    fun `validate should reject an http source that uses eventType without eventTypePath`() {
        val config = createValidConfig().let {
            it.copy(
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id"
                    )
                )
            )
        }

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "sources.stripe.eventTypePath")
    }

    @Test
    fun `validate should accept an http source with an eventTypePath`() {
        val config = createValidConfig().let {
            it.copy(
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        eventTypePath = "$.type"
                    )
                )
            )
        }

        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `validate should accept an http source with a topic template that avoids eventType`() {
        val config = createValidConfig().let {
            it.copy(
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        topic = "{{ source }}.received"
                    )
                )
            )
        }

        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `validate should reject a blank source topic template`() {
        val config = createValidConfig().let {
            it.copy(
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        eventTypePath = "$.type",
                        topic = "  "
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> { ConfigValidator.validate(config) }
    }

    // === F-040: destination URL validation ===

    private fun configWithBaseUrl(baseUrl: String, blockPrivateAddresses: Boolean = false): QueueBoxConfig =
        createValidConfig().let { base ->
            base.copy(
                http = HttpConfig(blockPrivateAddresses = blockPrivateAddresses),
                destinations = mapOf(
                    "webhook-api" to DestinationConfig.Http(
                        baseUrl = baseUrl,
                        path = "/webhooks",
                        timeoutMs = 30000
                    )
                ),
                routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook-api"))
            )
        }

    @Test
    fun `should fail when destination baseUrl has no scheme`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(configWithBaseUrl("api.example.com"))
        }
        assertContains(exception.message!!, "baseUrl")
    }

    @Test
    fun `should fail when destination baseUrl uses the file scheme`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(configWithBaseUrl("file:///etc/passwd"))
        }
        assertContains(exception.message!!, "http")
    }

    @Test
    fun `should fail when destination baseUrl is blank`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(configWithBaseUrl("   "))
        }
    }

    @Test
    fun `should pass when destination baseUrl carries a trailing slash`() {
        assertNotNull(ConfigValidator.validate(configWithBaseUrl("https://api.example.com/")))
    }

    @Test
    fun `should fail when blockPrivateAddresses refuses the metadata address`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(
                configWithBaseUrl("http://169.254.169.254/", blockPrivateAddresses = true)
            )
        }
        assertContains(exception.message!!, "private")
    }

    /**
     * Sixth review gate, defect B1.
     *
     * Every sibling message in `requirePublicHost` masked the URL. This one printed it raw, and
     * the failure becomes an uncaught exception at startup, which the JVM prints to stderr. The
     * user information check does not protect it, because a password QUERY PARAMETER is legal
     * there.
     */
    @Test
    fun `the private address failure never prints a password query parameter`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(
                configWithBaseUrl(
                    "http://127.0.0.1:9000/hook?password=Hunter2Secret",
                    blockPrivateAddresses = true
                )
            )
        }

        assertFalse(
            exception.message!!.contains("Hunter2Secret"),
            "the startup failure printed the password: ${exception.message}"
        )
        assertContains(exception.message!!, "private")
    }

    @Test
    fun `should fail when blockPrivateAddresses refuses loopback`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(
                configWithBaseUrl("http://127.0.0.1:9000", blockPrivateAddresses = true)
            )
        }
    }

    @Test
    fun `should fail when blockPrivateAddresses refuses a site-local address`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(
                configWithBaseUrl("http://10.1.2.3", blockPrivateAddresses = true)
            )
        }
    }

    @Test
    fun `should fail when blockPrivateAddresses refuses a unique-local IPv6 address`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(
                configWithBaseUrl("http://[fd00::1]", blockPrivateAddresses = true)
            )
        }
    }

    @Test
    fun `should pass when blockPrivateAddresses is off and the address is private`() {
        assertNotNull(
            ConfigValidator.validate(
                configWithBaseUrl("http://169.254.169.254/", blockPrivateAddresses = false)
            )
        )
    }

    @Test
    fun `should pass when the host does not resolve`() {
        assertNotNull(
            ConfigValidator.validate(
                configWithBaseUrl(
                    "https://host-that-does-not-resolve.invalid",
                    blockPrivateAddresses = true
                )
            )
        )
    }

    // --- F-040: the outbound URL rules that the audit added ---

    @Test
    fun `should fail when destination baseUrl carries user information`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://user:password@api.example.com",
                    path = "/hook"
                )
            ),
            routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook-api"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "must not carry a user name or a password")
        assertFalse(exception.message!!.contains("password@"), "The message must not echo the credential")
    }

    @Test
    fun `should fail when destination path carries a dot dot segment`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com/api/v1",
                    path = "/../../internal"
                )
            ),
            routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook-api"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "must not carry a '.' or a '..' segment")
    }

    @Test
    fun `should fail when the oauth2 token url is not http`() {
        val config = createValidConfig().copy(
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://api.example.com",
                    path = "/hook",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "client",
                        clientSecret = Secret("secret"),
                        tokenUrl = "file:///etc/passwd"
                    )
                )
            ),
            routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook-api"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "tokenUrl")
    }

    @Test
    fun `should fail when the oauth2 token url resolves to a private address`() {
        val config = createValidConfig().copy(
            http = HttpConfig(blockPrivateAddresses = true),
            destinations = mapOf(
                "webhook-api" to DestinationConfig.Http(
                    baseUrl = "https://example.com",
                    path = "/hook",
                    auth = DestinationAuthConfig.OAuth2(
                        clientId = "client",
                        clientSecret = Secret("secret"),
                        tokenUrl = "http://169.254.169.254/token"
                    )
                )
            ),
            routes = listOf(RouteConfig(topicPattern = "order.*", destination = "webhook-api"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "tokenUrl")
        assertContains(exception.message!!, "169.254.169.254")
    }

    // --- F-035: an unreachable HMAC configuration must fail at startup ---

    @Test
    fun `should fail when the timestamp format has no timestamp header`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("whsec"),
                        signaturePayloadFormat = SignaturePayloadFormat.TIMESTAMP_DOT_BODY
                    )
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "timestampHeader")
    }

    @Test
    fun `should fail when the timestamp tolerance is not positive`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.type",
                    auth = InboxAuthConfig.HmacSignature(
                        secret = Secret("whsec"),
                        timestampHeader = "X-Timestamp",
                        timestampTolerance = 0
                    )
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "timestampTolerance")
    }

    // === Extraction path validation (the fourth review gate) ===

    @Test
    fun `should fail when http source idempotencyKeyPath is indefinite`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$..orderId",
                    eventTypePath = "$.type"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "idempotencyKeyPath")
        assertContains(exception.message!!, "indefinite")
    }

    @Test
    fun `should fail when http source eventTypePath is indefinite`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.id",
                    eventTypePath = "$.events[*].type"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "eventTypePath")
        assertContains(exception.message!!, "indefinite")
    }

    @Test
    fun `should fail when rabbitmq source aggregateIdPath is indefinite`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    idempotencyKeyPath = "$.orderId",
                    aggregateIdPath = "$..customerId"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "aggregateIdPath")
        assertContains(exception.message!!, "indefinite")
    }

    @Test
    fun `should fail when an extraction path does not parse`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.[",
                    eventTypePath = "$.type"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            ConfigValidator.validate(config)
        }

        assertContains(exception.message!!, "not a valid JSONPath expression")
    }

    @Test
    fun `should pass when every extraction path is definite`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "stripe-webhooks" to SourceConfig.Http(
                    path = "/webhooks/stripe",
                    idempotencyKeyPath = "$.data.orderId",
                    aggregateIdPath = "$.data.customerId",
                    eventTypePath = "$.type"
                )
            )
        )

        val validated = ConfigValidator.validate(config)

        assertNotNull(validated)
    }
    // === RabbitMQ source event type. Fifth review gate. ===

    @Test
    fun `the default topic of a rabbitmq source does not use the event type`() {
        val source = SourceConfig.RabbitMQ(
            queueName = "orders",
            connectionUrl = "amqp://localhost:5672"
        )

        assertEquals(
            "{{ source }}",
            source.topic,
            "The default must render from a value that every AMQP message carries."
        )

        val validated = ConfigValidator.validate(
            createValidConfig().copy(sources = mapOf("order-events" to source))
        )

        assertNotNull(validated)
    }

    @Test
    fun `should fail when a rabbitmq source topic uses the event type with no source for it`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    topic = "{{ eventType }}"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { ConfigValidator.validate(config) }

        assertContains(exception.message!!, "order-events")
        assertContains(exception.message!!, "sources.order-events.eventTypePath")
        assertContains(exception.message!!, "eventTypeFromHeader")
    }

    @Test
    fun `should pass when a rabbitmq source topic uses the event type with an event type path`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    eventTypePath = "$.type",
                    topic = "{{ source }}.{{ eventType }}"
                )
            )
        )

        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `should pass when a rabbitmq source declares the event type header`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    eventTypeFromHeader = true,
                    topic = "{{ eventType }}"
                )
            )
        )

        assertNotNull(ConfigValidator.validate(config))
    }

    @Test
    fun `should fail when a rabbitmq source event type path is indefinite`() {
        val config = createValidConfig().copy(
            sources = mapOf(
                "order-events" to SourceConfig.RabbitMQ(
                    queueName = "orders",
                    connectionUrl = "amqp://localhost:5672",
                    eventTypePath = "$..type"
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { ConfigValidator.validate(config) }

        assertContains(exception.message!!, "eventTypePath")
    }
}
