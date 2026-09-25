package org.nxtspec.e2e

import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.http.HttpPublisher
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * F-055.
 *
 * Takes the requeue SQL out of the docs page `site/src/content/docs/operations/dead-letters.mdx`
 * and runs it against a dead-lettered message. Asserts the destination then receives the message.
 *
 * The SQL is never pasted into this test. The page is the single source.
 */
class E2EDeadLetterReplayTest : E2ETestBase() {

    private companion object {
        const val DEAD_LETTER_PAGE = "site/src/content/docs/operations/dead-letters.mdx"
    }

    private var poller: OutboxPoller? = null

    @AfterEach
    fun shutdownPoller() {
        runBlocking { poller?.shutdown() }
        poller = null
    }

    private fun deadLetterDocument(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, DEAD_LETTER_PAGE).isFile) {
            dir = dir.parentFile ?: fail("$DEAD_LETTER_PAGE not found")
        }
        return File(dir, DEAD_LETTER_PAGE)
    }

    /**
     * Reads the named fenced sql block. The page states the `sql-id` convention. MDX has no HTML
     * comment, so the name is an MDX comment: {/* sql-id: name */}.
     */
    private fun namedSqlBlock(id: String): String {
        val pattern = Regex(
            "\\{/\\*\\s*sql-id:\\s*$id\\s*\\*/\\}\\s*```sql\\n(.*?)```",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = pattern.find(deadLetterDocument().readText())
            ?: fail("No sql block named '$id' in $DEAD_LETTER_PAGE")
        return match.groupValues[1].trim().removeSuffix(";").trim()
    }

    private fun executeSql(sql: String): Int = dataSource.connection.use { connection ->
        val updated = connection.createStatement().use { it.executeUpdate(sql) }
        if (!connection.autoCommit) connection.commit()
        updated
    }

    private fun markDead(id: UUID) {
        executeSql(
            "UPDATE outbox SET state = 'dead', attempt = 3, last_error = 'destination refused', " +
                "claimed_at = CURRENT_TIMESTAMP WHERE id = '$id'"
        )
    }

    @Test
    fun `the documented requeue sql delivers a dead-lettered message`() = runBlocking {
        val mockServer = startMockHttpServer(
            responseCode = HttpStatusCode.OK,
            responseBody = """{"accepted": true}"""
        )

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
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        val publisher = HttpPublisher()
        poller = OutboxPoller(
            config = config,
            repository = OutboxRepository(),
            router = router,
            publishers = listOf(publisher),
            retryStrategy = RetryStrategy(config)
        )

        // A dead message that the poller must ignore.
        val messageId = insertOutboxMessage(
            topic = "order.created",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("order-replay-1")))
        )
        markDead(messageId)

        poller?.start()

        // The poller must not deliver a dead message.
        assertTrue(
            !awaitUntil(timeoutMs = 1000) { mockServer.receivedRequests.isNotEmpty() },
            "A dead message must stay undelivered"
        )
        assertEquals("dead", getOutboxMessageState(messageId))

        // Run the documented requeue statement.
        val requeueSql = namedSqlBlock("requeue-one").replace(":message_id", "'$messageId'")
        val updated = executeSql(requeueSql)
        assertEquals(1, updated, "The documented requeue must update exactly one row")

        // The requeued message is delivered.
        assertTrue(
            awaitUntil { mockServer.receivedRequests.isNotEmpty() },
            "The requeued message must reach the destination"
        )
        assertTrue(mockServer.receivedRequests[0].body.contains("order-replay-1"))
        assertEquals("/webhook", mockServer.receivedRequests[0].path)

        assertTrue(
            awaitUntil { getOutboxMessageState(messageId) == "sent" },
            "The requeued message must end in the state 'sent'"
        )

        publisher.close()
    }

    @Test
    fun `the documented requeue sql does not touch a message that is not dead`() = runBlocking {
        val messageId = insertOutboxMessage(topic = "order.created")
        val requeueSql = namedSqlBlock("requeue-one").replace(":message_id", "'$messageId'")
        assertEquals(0, executeSql(requeueSql), "The requeue must only affect a dead message")
    }
}
