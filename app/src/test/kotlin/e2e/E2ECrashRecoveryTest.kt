package org.nxtspec.e2e

import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.OutboxTable
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.http.HttpPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Covers F-006. A message that a crashed replica left in state 'processing' must be delivered
 * after the restart.
 */
class E2ECrashRecoveryTest : E2ETestBase() {

    private var poller: OutboxPoller? = null

    @AfterEach
    fun shutdownPoller() {
        runBlocking { poller?.shutdown() }
        poller = null
    }

    @Test
    fun `should deliver a message that a crashed replica left in processing`() = runBlocking {
        val mockServer = startMockHttpServer(responseCode = HttpStatusCode.OK)

        val destination = Destination.Http(
            name = "test-http",
            baseUrl = mockServer.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = "order.*", destination = "test-http")),
            destinations = mapOf("test-http" to destination)
        )

        val repository = OutboxRepository()
        val messageId = insertOutboxMessage(
            topic = "order.created",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("order-1")))
        )

        // The first replica claims the message and then dies before it publishes.
        val claimed = repository.claimBatch(10)
        assertEquals(listOf(messageId), claimed.map { it.id })
        assertEquals("processing", getOutboxMessageState(messageId))

        // The claim is older than the visibility timeout.
        backdateClaimedAt(messageId)

        // The replacement replica starts.
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3,
            claimTimeoutMs = 1000
        )
        poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(HttpPublisher()),
            retryStrategy = RetryStrategy(config)
        )
        poller!!.start()

        awaitUntil { getOutboxMessageState(messageId) == "sent" }

        assertEquals("sent", getOutboxMessageState(messageId))
        assertTrue(mockServer.receivedRequests.isNotEmpty(), "The message must reach the destination")

        val (_, attempt) = getOutboxMessageStateAndAttempt(messageId)
        assertEquals(0, attempt, "A reclaim must not count as a delivery attempt")
    }

    private fun backdateClaimedAt(id: UUID) {
        transaction {
            OutboxTable.update({ OutboxTable.id eq id }) {
                it[OutboxTable.claimedAt] = Clock.System.now() - 10.minutes
            }
        }
    }
}
