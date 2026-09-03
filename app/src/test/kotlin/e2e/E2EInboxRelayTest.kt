package org.nxtspec.e2e

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.ExposedTransactionRunner
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxConfig
import org.nxtspec.InboxHandler
import org.nxtspec.InboxMessage
import org.nxtspec.InboxRelay
import org.nxtspec.InboxRelayConfig
import org.nxtspec.InboxRepository
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.OutboxTable
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.SourceConfig
import org.nxtspec.configureInboxRoutes
import org.nxtspec.http.HttpPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers F-002. The relay forwards a stored inbox message into the outbox table, and the outbox
 * machinery delivers it.
 */
class E2EInboxRelayTest : E2ETestBase() {

    private var poller: OutboxPoller? = null
    private var relay: InboxRelay? = null

    @AfterEach
    fun shutdownServices() {
        runBlocking {
            poller?.shutdown()
            relay?.shutdown()
        }
        poller = null
        relay = null
    }

    @Test
    fun `should forward a received webhook to the destination`() = testApplication {
        val mockServer = startMockHttpServer(responseCode = HttpStatusCode.OK)

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            val repository = InboxRepository()
            val handler = InboxHandler(repository, IdempotencyExtractor())
            val sources = mapOf(
                "stripe" to SourceConfig.Http(
                    path = "/stripe",
                    idempotencyKeyPath = "$.id",
                    aggregateIdPath = "$.customer",
                    eventTypePath = "$.type",
                    topic = "{{ source }}.{{ eventType }}"
                )
            )
            configureInboxRoutes(InboxConfig(basePath = "/inbox"), sources, handler)
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "evt_relay_1", "type": "payment.succeeded", "customer": "cus_1", "amount": 5000}""")
        }
        assertTrue(response.status.isSuccess(), "The inbox must accept the webhook")

        val stored = getInboxMessage("stripe", "evt_relay_1")
        assertNotNull(stored)
        assertEquals("pending", stored.state)

        // The relay forwards the stored message into the outbox.
        val inboxRepository = InboxRepository()
        val outboxRepository = OutboxRepository()
        relay = InboxRelay(
            config = InboxRelayConfig(pollIntervalMs = 50, batchSize = 10),
            inboxRepository = inboxRepository,
            outboxRepository = outboxRepository,
            transactionRunner = ExposedTransactionRunner(),
            sourceTopicTemplates = mapOf("stripe" to "{{ source }}.{{ eventType }}")
        )

        // The outbox machinery delivers the forwarded message.
        val destination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val outboxConfig = OutboxConfig(pollIntervalMs = 50, batchSize = 10, retryBaseDelayMs = 100, maxAttempts = 3)
        poller = OutboxPoller(
            config = outboxConfig,
            repository = outboxRepository,
            router = MessageRouter(
                routes = listOf(RouteConfig(topicPattern = "stripe.**", destination = "test-http")),
                destinations = mapOf("test-http" to destination)
            ),
            publishers = listOf(HttpPublisher()),
            retryStrategy = RetryStrategy(outboxConfig)
        )

        relay!!.start()
        poller!!.start()

        awaitUntil { mockServer.receivedRequests.isNotEmpty() }

        // 1. The inbox row reaches 'processed'.
        assertEquals("processed", getInboxMessage("stripe", "evt_relay_1")!!.state)

        // 2. A matching outbox row exists with the mapped topic, key and headers.
        val outboxRow = findOutboxRowByHeader("x-idempotency-key", "evt_relay_1")
        assertNotNull(outboxRow, "The relay must insert an outbox row")
        assertEquals("stripe.payment.succeeded", outboxRow.topic)
        assertEquals("cus_1", outboxRow.key)
        assertEquals("stripe", outboxRow.headers["x-source"])
        assertEquals(stored.id.toString(), outboxRow.headers["x-inbox-id"])

        // 3. The message reaches the configured destination.
        assertTrue(mockServer.receivedRequests.isNotEmpty(), "The destination must receive the message")
        assertTrue(mockServer.receivedRequests.first().body.contains("5000"))
    }

    @Test
    fun `two relay replicas forwarding 100 messages produce exactly 100 outbox rows`() = runBlocking {
        val inboxRepository = InboxRepository()
        val outboxRepository = OutboxRepository()

        repeat(100) { index ->
            inboxRepository.store(
                InboxMessage(
                    id = UUID.randomUUID(),
                    source = "stripe",
                    idempotencyKey = "evt_$index",
                    eventType = "payment.succeeded",
                    payload = JsonObject(mapOf("index" to JsonPrimitive(index)))
                )
            )
        }

        fun newRelay() = InboxRelay(
            config = InboxRelayConfig(pollIntervalMs = 20, batchSize = 25),
            inboxRepository = inboxRepository,
            outboxRepository = outboxRepository,
            transactionRunner = ExposedTransactionRunner()
        )

        val first = newRelay()
        val second = newRelay()
        first.start()
        second.start()

        awaitUntil { countOutboxRows() >= 100 }

        first.shutdown()
        second.shutdown()

        assertEquals(100, countOutboxRows(), "Two replicas must forward each message exactly once")
        assertEquals(100L, inboxRepository.countByState("processed"))
    }

    @Test
    fun `a failed outbox insert leaves the inbox row recoverable`() = runBlocking {
        val inboxRepository = InboxRepository()
        val failingOutbox = org.nxtspec.repository.OutboxRepositoryInterface::class.java.let {
            object : org.nxtspec.repository.OutboxRepositoryInterface {
                override suspend fun claimBatch(batchSize: Int) = emptyList<org.nxtspec.OutboxMessage>()
                override suspend fun insert(message: org.nxtspec.OutboxMessage) {
                    throw IllegalStateException("insert failed")
                }
                override suspend fun markSent(id: UUID) = Unit
                        override suspend fun scheduleRetry(id: UUID, delayMs: Long, error: String?) = Unit
                override suspend fun markDead(id: UUID, error: String?) = Unit
                override suspend fun countByState(state: String): Long = 0
                override suspend fun reclaimStale(olderThan: kotlin.time.Duration): Int = 0
                override suspend fun deleteOlderThan(
                    state: String,
                    cutoff: kotlinx.datetime.Instant,
                    limit: Int
                ): Int = 0
                override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int = 0
            }
        }

        inboxRepository.store(
            InboxMessage(
                source = "stripe",
                idempotencyKey = "evt_fail_1",
                eventType = "payment.succeeded",
                payload = JsonObject(mapOf("amount" to JsonPrimitive(1)))
            )
        )

        val relayUnderTest = InboxRelay(
            config = InboxRelayConfig(batchSize = 10),
            inboxRepository = inboxRepository,
            outboxRepository = failingOutbox,
            transactionRunner = ExposedTransactionRunner()
        )

        assertEquals(0, relayUnderTest.relayBatch())

        // The row stays claimed, so no other replica takes it.
        assertEquals("processing", getInboxMessage("stripe", "evt_fail_1")!!.state)

        // The F-006 reclaim returns it to 'pending' after the visibility timeout.
        assertEquals(1, inboxRepository.reclaimStale(kotlin.time.Duration.ZERO))
        assertEquals("pending", getInboxMessage("stripe", "evt_fail_1")!!.state)
    }

    private data class OutboxRow(val topic: String, val key: String?, val headers: Map<String, String>)

    private fun findOutboxRowByHeader(header: String, value: String): OutboxRow? = transaction {
        OutboxTable.selectAll().mapNotNull { row ->
            val headersJson = row[OutboxTable.headers]
            val headers = (headersJson as? JsonObject)
                ?.mapValues { it.value.toString().trim('"') }
                ?: emptyMap()
            if (headers[header] == value) {
                OutboxRow(row[OutboxTable.topic], row[OutboxTable.key], headers)
            } else {
                null
            }
        }.firstOrNull()
    }

    private fun countOutboxRows(): Int = transaction {
        OutboxTable.selectAll().count().toInt()
    }
}
