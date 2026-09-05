package org.nxtspec.e2e

import io.ktor.http.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxHandler
import org.nxtspec.InboxHandlerResult
import org.nxtspec.InboxRepository
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.SourceConfig
import org.nxtspec.app.MetricsCollector
import org.nxtspec.http.HttpPublisher
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E tests verifying that metrics are recorded end-to-end when processing messages.
 */
class E2EMetricsFlowTest : E2ETestBase() {

    private var poller: OutboxPoller? = null
    private var httpPublisher: HttpPublisher? = null

    @AfterEach
    fun shutdownComponents() {
        runBlocking {
            poller?.shutdown()
        }
        poller = null
        httpPublisher?.close()
        httpPublisher = null
    }

    @Test
    fun `should record outbox metrics when message successfully delivered`() = runBlocking {
        // Setup Prometheus registry and metrics collector
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metricsCollector = MetricsCollector(prometheusRegistry)

        // Start mock HTTP server
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.OK,
            responseBody = """{"accepted": true}"""
        )

        // Configure router with HTTP destination
        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "metrics.*",
                destination = "test-http"
            )
        )
        val router = MessageRouter(
            routes = routes,
            destinations = mapOf("test-http" to httpDestination)
        )

        // Create repository and poller with metrics
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        httpPublisher = HttpPublisher(metricsCollector = metricsCollector)
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(httpPublisher!!),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        // Insert message into outbox
        val payload = JsonObject(
            mapOf(
                "testId" to JsonPrimitive("metrics-test-1")
            )
        )
        insertOutboxMessage(
            topic = "metrics.test",
            payload = payload
        )

        // Start poller and wait for delivery
        poller?.start()

        var delivered = false
        repeat(50) {
            delay(50)
            if (mockServer.receivedRequests.isNotEmpty()) {
                delivered = true
                return@repeat
            }
        }

        assertTrue(delivered, "Message should be delivered")
        delay(100) // Allow metrics to be recorded

        // Verify metrics
        val metricsOutput = prometheusRegistry.scrape()

        // Verify sent counter is incremented
        assertTrue(
            metricsOutput.contains("queuebox_outbox_messages_total{status=\"sent\""),
            "Should have sent message counter"
        )

        // Verify processing duration is recorded
        assertTrue(
            metricsOutput.contains("queuebox_outbox_processing_duration_seconds"),
            "Should have processing duration metric"
        )

        // Verify publish duration is recorded for HTTP
        assertTrue(
            metricsOutput.contains("queuebox_outbox_publish_duration_seconds"),
            "Should have publish duration metric"
        )
        assertTrue(
            metricsOutput.contains("destination_type=\"http\""),
            "Should have http destination type tag"
        )

        // Verify pending count was updated
        assertTrue(
            metricsOutput.contains("queuebox_outbox_messages_pending"),
            "Should have pending messages gauge"
        )
    }

    @Test
    fun `should record failed and dead metrics when delivery fails`() = runBlocking {
        // Setup Prometheus registry and metrics collector
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metricsCollector = MetricsCollector(prometheusRegistry)

        // Start mock HTTP server that returns 500
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.InternalServerError,
            responseBody = """{"error": "simulated failure"}"""
        )

        // Configure router
        val httpDestination = Destination.Http(
            name = "failing-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 2000
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = "fail.*", destination = "failing-http")),
            destinations = mapOf("failing-http" to httpDestination)
        )

        // Create poller with fast retry
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 50,
            maxAttempts = 2
        )
        httpPublisher = HttpPublisher(metricsCollector = metricsCollector)
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(httpPublisher!!),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        // Insert message with maxAttempts=2
        val messageId = insertOutboxMessage(
            topic = "fail.test",
            payload = JsonObject(mapOf("test" to JsonPrimitive("retry-test"))),
            maxAttempts = 2
        )

        // Start poller and wait for message to be marked dead
        poller?.start()

        repeat(50) {
            delay(100)
            val state = getOutboxMessageState(messageId)
            if (state == "dead") return@repeat
        }

        assertEquals("dead", getOutboxMessageState(messageId))
        delay(100) // Allow metrics to be recorded

        // Verify metrics
        val metricsOutput = prometheusRegistry.scrape()

        // Verify failed counter is incremented (at least once for retry)
        assertTrue(
            metricsOutput.contains("queuebox_outbox_messages_total{status=\"failed\""),
            "Should have failed message counter"
        )

        // Verify dead counter is incremented
        assertTrue(
            metricsOutput.contains("queuebox_outbox_messages_total{status=\"dead\""),
            "Should have dead message counter"
        )
    }

    @Test
    fun `should record dead metrics when no route matches`() = runBlocking {
        // Setup Prometheus registry and metrics collector
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metricsCollector = MetricsCollector(prometheusRegistry)

        // Configure router with specific pattern
        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = "http://localhost:8888",
            path = "/webhook"
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "specific.topic.only",
                destination = "test-http"
            )
        )
        val router = MessageRouter(
            routes = routes,
            destinations = mapOf("test-http" to httpDestination)
        )

        // Create poller
        val repository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        httpPublisher = HttpPublisher(metricsCollector = metricsCollector)
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(httpPublisher!!),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        // Insert message with non-matching topic
        val messageId = insertOutboxMessage(
            topic = "unmatched.topic",
            payload = JsonObject(emptyMap())
        )

        // Start poller and wait for processing
        poller?.start()
        delay(500)

        assertEquals("dead", getOutboxMessageState(messageId))

        // Verify metrics
        val metricsOutput = prometheusRegistry.scrape()

        // Verify dead counter is incremented
        assertTrue(
            metricsOutput.contains("queuebox_outbox_messages_total{status=\"dead\""),
            "Should have dead message counter for unroutable message"
        )
    }

    @Test
    fun `should record inbox metrics when messages stored and duplicated`() = runBlocking {
        // Setup Prometheus registry and metrics collector
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metricsCollector = MetricsCollector(prometheusRegistry)

        // Create inbox handler with metrics
        val inboxRepository = InboxRepository()
        val extractor = IdempotencyExtractor()
        val inboxHandler = InboxHandler(inboxRepository, extractor, metricsCollector)

        // Create source config
        val sourceConfig = SourceConfig.Http(
            path = "/test",
            idempotencyKeyPath = "$.id"
        )

        // Store first message
        val payload1 = JsonObject(mapOf("id" to JsonPrimitive("unique-key-1")))
        val result1 = inboxHandler.handle("test-source", sourceConfig, payload1)
        assertTrue(result1 is InboxHandlerResult.Accepted, "First message should be accepted")

        // Store second message with same idempotency key (should be duplicate)
        val result2 = inboxHandler.handle("test-source", sourceConfig, payload1)
        assertTrue(result2 is InboxHandlerResult.Duplicate, "Second message should be duplicate")

        // Store third message with different key
        val payload3 = JsonObject(mapOf("id" to JsonPrimitive("unique-key-2")))
        val result3 = inboxHandler.handle("test-source", sourceConfig, payload3)
        assertTrue(result3 is InboxHandlerResult.Accepted, "Third message should be accepted")

        // Verify metrics
        val metricsOutput = prometheusRegistry.scrape()

        // Verify new inbox counter (should be 2)
        assertTrue(
            metricsOutput.contains("queuebox_inbox_messages_total{status=\"new\""),
            "Should have new inbox message counter"
        )

        // Verify duplicate inbox counter (should be 1)
        assertTrue(
            metricsOutput.contains("queuebox_inbox_messages_total{status=\"duplicate\""),
            "Should have duplicate inbox message counter"
        )
    }

    @Test
    fun `should record all metrics in end-to-end flow`() = runBlocking {
        // Setup Prometheus registry and metrics collector
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metricsCollector = MetricsCollector(prometheusRegistry)

        // Setup outbox components
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.OK,
            responseBody = """{"ok": true}"""
        )

        val httpDestination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = "e2e.*", destination = "test-http")),
            destinations = mapOf("test-http" to httpDestination)
        )

        val outboxRepository = OutboxRepository()
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        httpPublisher = HttpPublisher(metricsCollector = metricsCollector)
        val retryStrategy = RetryStrategy(config)

        poller = OutboxPoller(
            config = config,
            repository = outboxRepository,
            router = router,
            publishers = listOf(httpPublisher!!),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        // Setup inbox components
        val inboxRepository = InboxRepository()
        val extractor = IdempotencyExtractor()
        val inboxHandler = InboxHandler(inboxRepository, extractor, metricsCollector)
        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )

        // Insert outbox messages
        repeat(3) { i ->
            insertOutboxMessage(
                topic = "e2e.test",
                payload = JsonObject(mapOf("index" to JsonPrimitive(i)))
            )
        }

        // Start poller and wait for delivery
        poller?.start()

        // `return@repeat` continues the loop, so the old wait always ran to its end and could
        // count a redelivery that a reclaim started under load. `awaitUntil` leaves as soon as
        // the three deliveries arrived.
        awaitUntil { mockServer.receivedRequests.size >= 3 }

        assertEquals(3, mockServer.receivedRequests.size)

        // Process inbox messages
        val inboxPayload = JsonObject(mapOf("id" to JsonPrimitive("inbox-1")))
        inboxHandler.handle("test-source", sourceConfig, inboxPayload) // new
        inboxHandler.handle("test-source", sourceConfig, inboxPayload) // duplicate

        delay(100) // Allow metrics to settle

        // Verify all metrics are present
        val metricsOutput = prometheusRegistry.scrape()

        // Outbox metrics
        assertTrue(metricsOutput.contains("queuebox_outbox_messages_total{status=\"sent\""))
        assertTrue(metricsOutput.contains("queuebox_outbox_processing_duration_seconds"))
        assertTrue(metricsOutput.contains("queuebox_outbox_publish_duration_seconds"))
        assertTrue(metricsOutput.contains("queuebox_outbox_messages_pending"))

        // Inbox metrics
        assertTrue(metricsOutput.contains("queuebox_inbox_messages_total{status=\"new\""))
        assertTrue(metricsOutput.contains("queuebox_inbox_messages_total{status=\"duplicate\""))

        // Note: queuebox_uptime_seconds and queuebox_info are registered by QueueBoxMetrics
        // when MetricsCollector is instantiated, so they should be present
    }
}
