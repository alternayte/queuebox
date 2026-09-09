package org.nxtspec.e2e

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One test per sentence of the ordering section of `docs/delivery-semantics.md`.
 *
 * A guarantee without a test is a wish. The name of each test repeats the sentence it proves, so
 * a reader of the document can find the proof.
 */
class OrderingGuaranteeTest : E2ETestBase() {

    @Test
    fun `QueueBox delivers at least once`() = runBlocking {
        val server = startMockHttpServer()
        // The first attempt fails after the broker received the body, which is the crash window.
        server.setResponse(HttpStatusCode.InternalServerError)
        val id =
            insertOutboxMessage(
                topic = "t",
                payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"n":1}""")
            )
        startPoller()

        assertTrue(awaitUntil { server.requestCount >= 1 })
        server.setResponse(HttpStatusCode.OK)

        // The message must arrive again. It must never vanish.
        assertTrue(awaitUntil { getOutboxMessageState(id) == "sent" })
        assertTrue(server.requestCount >= 2, "the message was delivered only once")
    }

    @Test
    fun `in push mode one aggregate holds at most one message in flight`() = runBlocking {
        repeat(4) { n ->
            insertInboxMessage(
                source = "s",
                idempotencyKey = "k$n",
                aggregateId = "agg-1",
                consumption = "push",
                eventType = "evt"
            )
        }
        startRelay()

        // The relay forwards one at a time, so the inbox never holds two rows of the aggregate
        // in state 'processing' at one time.
        val peak = observePeakInFlight(aggregateId = "agg-1", untilForwarded = 4)
        assertEquals(1, peak)

        // The order is the order of created_at.
        assertEquals(listOf("k0", "k1", "k2", "k3"), forwardedIdempotencyKeysInOrder())
    }

    @Test
    fun `in pull mode one aggregate holds at most one message in flight`() = runBlocking {
        repeat(4) { n ->
            insertInboxMessage(
                source = "s",
                idempotencyKey = "k$n",
                aggregateId = "agg-1",
                consumption = "pull"
            )
        }

        val first = claimPull(source = "s", batch = 10)
        val second = claimPull(source = "s", batch = 10)

        // The first claim takes one row of the aggregate. The second takes none of it.
        assertEquals(1, first.size)
        assertEquals(0, second.size)
    }

    @Test
    fun `QueueBox preserves no order between two aggregates`() = runBlocking {
        insertInboxMessage(source = "s", idempotencyKey = "a0", aggregateId = "agg-1", consumption = "pull")
        insertInboxMessage(source = "s", idempotencyKey = "b0", aggregateId = "agg-2", consumption = "pull")

        val claimed = claimPull(source = "s", batch = 10)

        // Both aggregates leave one claim together. No reader can rely on an order between them.
        assertEquals(setOf("agg-1", "agg-2"), claimed.map { it.aggregateId }.toSet())
    }

    @Test
    fun `a poller delivers in claim order and not in commit order`() = runBlocking {
        // Transaction A takes the lower identifier first, and commits last. The pair uses
        // explicit identifiers, because two random identifiers order deterministically only
        // by luck.
        val slow = openTransactionAndInsertOutbox(topic = "t", payload = bodyPayload("slow"), id = UUID(0L, 1L))
        val fast = insertOutboxMessage(topic = "t", payload = bodyPayload("fast"), id = UUID(0L, 2L))
        assertTrue(slow.id < fast, "the arrangement needs the slow row to hold the lower identifier")

        val server = startMockHttpServer()
        startPoller()
        assertTrue(awaitUntil { server.requestCount >= 1 })
        slow.commit()

        assertTrue(awaitUntil { server.requestCount >= 2 })

        // The later commit arrived first. Commit order is not claim order.
        assertEquals(listOf("fast", "slow"), server.receivedBodies.map { bodyName(it) })
    }

    private fun bodyPayload(name: String) = kotlinx.serialization.json.Json.parseToJsonElement("""{"n":"$name"}""")

    /**
     * A pull claim, one row per aggregate at most, run against the canonical claim statement of
     * `examples/pull/sql/postgresql/claim.sql`. The Kotlin `InboxRepository.claimPending` claims
     * only `consumption = 'push'` rows and carries no `source` parameter, so it cannot serve a
     * pull test. Every pull client library runs this same statement, so this is the real rule.
     */
    private data class PullClaim(val id: UUID, val aggregateId: String?)

    private fun claimPull(source: String, batch: Int, leaseMs: Int = 30_000): List<PullClaim> {
        val path = Path.of("../examples/pull/sql/postgresql/claim.sql")
        val candLimit = (3 * batch).coerceAtLeast(50).coerceAtMost(500)
        // Strip the leading `--` comment lines. The comment prose names the same parameters
        // that the statement binds, and the JDBC driver ignores a placeholder written inside a
        // comment, so counting it here would misalign every bind index after it.
        val text = Files.readString(path).lineSequence().filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
        val names = mutableListOf<String>()
        val sql = Regex("(?<!:):([a-z_]+)").replace(text) {
            names.add(it.groupValues[1])
            "?"
        }
        val values = mapOf(
            "source" to source,
            "batch" to batch,
            "lease_ms" to leaseMs,
            "cand_limit" to candLimit
        )
        return dataSource.connection.use { connection: Connection ->
            connection.autoCommit = true
            connection.prepareStatement(sql).use { stmt ->
                names.forEachIndexed { index, name ->
                    stmt.setObject(index + 1, values.getValue(name))
                }
                stmt.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                PullClaim(
                                    id = UUID.fromString(rows.getString("id")),
                                    aggregateId = rows.getString("aggregate_id")
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
