package org.nxtspec.e2e

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.ExposedTransactionRunner
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxConfig
import org.nxtspec.InboxHandler
import org.nxtspec.InboxRelay
import org.nxtspec.InboxRelayConfig
import org.nxtspec.InboxRepository
import org.nxtspec.InboxTable
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.SourceConfig
import org.nxtspec.configureInboxRoutes
import org.nxtspec.http.HttpPublisher
import org.nxtspec.logging.CORRELATION_ID_HEADER
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers F-047. One identifier follows the message from the inbound request to the outbound
 * publish.
 */
class E2ECorrelationTest : E2ETestBase() {

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
    fun `the correlation identifier reaches the row and the outbound request`() = testApplication {
        val mockServer = startMockHttpServer()
        val correlationId = "corr-e2e-12345"

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureInboxRoutes(
                config = InboxConfig(basePath = "/inbox"),
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        eventTypePath = "$.type",
                        topic = "{{ source }}.{{ eventType }}"
                    )
                ),
                handler = InboxHandler(InboxRepository(), IdempotencyExtractor())
            )
        }

        // 1. The inbound request carries the identifier.
        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            header(CORRELATION_ID_HEADER, correlationId)
            setBody("""{"id":"evt_corr_1","type":"payment.succeeded"}""")
        }
        assertTrue(response.status.isSuccess())
        assertEquals(
            correlationId,
            response.headers[CORRELATION_ID_HEADER],
            "The response must echo the identifier"
        )

        // 2. The database row carries the identifier.
        val stored = readInboxCorrelationId("stripe", "evt_corr_1")
        assertEquals(correlationId, stored, "The inbox row must carry the identifier")

        // 3. The outbound request carries the identifier.
        val outboxRepository = OutboxRepository()
        relay = InboxRelay(
            config = InboxRelayConfig(pollIntervalMs = 30, batchSize = 10),
            inboxRepository = InboxRepository(),
            outboxRepository = outboxRepository,
            transactionRunner = ExposedTransactionRunner(),
            sourceTopicTemplates = mapOf("stripe" to "{{ source }}.{{ eventType }}")
        )

        val destination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val outboxConfig = OutboxConfig(pollIntervalMs = 30, batchSize = 10, retryBaseDelayMs = 50)
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

        val outbound = mockServer.receivedRequests.firstOrNull()
        assertNotNull(outbound, "The destination must receive the message")
        assertEquals(
            correlationId,
            outbound.headers[CORRELATION_ID_HEADER],
            "The outbound request must carry the identifier. Saw: ${outbound.headers}"
        )
    }

    @Test
    fun `the inbox generates an identifier when the caller sends none`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureInboxRoutes(
                config = InboxConfig(basePath = "/inbox"),
                sources = mapOf(
                    "stripe" to SourceConfig.Http(
                        path = "/stripe",
                        idempotencyKeyPath = "$.id",
                        eventTypePath = "$.type"
                    )
                ),
                handler = InboxHandler(InboxRepository(), IdempotencyExtractor())
            )
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"evt_corr_2","type":"payment.succeeded"}""")
        }

        val generated = response.headers[CORRELATION_ID_HEADER]
        assertNotNull(generated, "The response must carry a generated identifier")
        assertEquals(generated, readInboxCorrelationId("stripe", "evt_corr_2"))
    }

    private fun readInboxCorrelationId(source: String, idempotencyKey: String): String? = transaction {
        InboxTable.selectAll()
            .where {
                (InboxTable.messageSrc eq source) and (InboxTable.idempotencyKey eq idempotencyKey)
            }
            .single()[InboxTable.correlationId]
    }
}
