package org.nxtspec.e2e

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.nxtspec.InboxRepository
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
        // This exercises the same at-least-once contract as a crash between the publish and the
        // mark sent, but not that crash itself: the destination's first reply is a transient
        // failure (500), not a process death. Both leave the row unmarked after one delivery
        // attempt, so both must retry, which is the sentence under test.
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
    fun `the relay reserves one in-flight message per aggregate_id across every source together`() = runBlocking {
        // Two sources, one aggregate. Push claims no source term, so a claim call must not
        // return a second row of "agg-1" while the first is still 'processing', whatever source
        // either row carries. Claiming directly, twice, with no mark-processed between the two
        // calls leaves the first row 'processing' and un-advanced, which is exactly the state a
        // second, concurrent claimer would see; it needs no timing luck, unlike racing two
        // relays against the short gap between one relay's claim and its later mark.
        listOf("s1", "s2", "s1", "s2").forEachIndexed { n, source ->
            insertInboxMessage(
                source = source,
                idempotencyKey = "k$n",
                aggregateId = "agg-1",
                consumption = "push",
                eventType = "evt"
            )
        }
        val repository = InboxRepository()
        val first = repository.claimPending(1, 30_000)
        val second = repository.claimPending(3, 30_000)

        assertEquals(1, first.size)
        assertEquals(0, second.count { it.aggregateId == "agg-1" })
    }

    @Test
    fun `the relay forwards the messages of one aggregate one at a time, in the order of created_at`() = runBlocking {
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

        assertTrue(awaitUntil { forwardedIdempotencyKeysInOrder().size >= 4 })
        assertEquals(listOf("k0", "k1", "k2", "k3"), forwardedIdempotencyKeysInOrder())
    }

    @Test
    fun `in pull mode the claim reserves one in-flight message per (source, aggregate_id)`() = runBlocking {
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
    fun `a pull claim on one source never blocks a pull claim of the same aggregate on a different source`() =
        runBlocking {
            insertInboxMessage(source = "orders", idempotencyKey = "o0", aggregateId = "agg-x", consumption = "pull")
            insertInboxMessage(source = "payments", idempotencyKey = "p0", aggregateId = "agg-x", consumption = "pull")

            val ordersClaim = claimPull(source = "orders", batch = 10)
            val paymentsClaim = claimPull(source = "payments", batch = 10)

            // Both sources claim their own row of the same aggregate. Neither sees the other.
            assertEquals(1, ordersClaim.size)
            assertEquals(1, paymentsClaim.size)
        }

    @Test
    fun `QueueBox preserves no order between two different aggregates`() = runBlocking {
        insertInboxMessage(source = "s", idempotencyKey = "a0", aggregateId = "agg-1", consumption = "pull")
        insertInboxMessage(source = "s", idempotencyKey = "b0", aggregateId = "agg-2", consumption = "pull")

        val claimed = claimPull(source = "s", batch = 10)

        // Both aggregates leave one claim together. No reader can rely on an order between them.
        assertEquals(setOf("agg-1", "agg-2"), claimed.map { it.aggregateId }.toSet())
    }

    @Test
    fun `a row with no aggregate_id takes part in no ordering`() = runBlocking {
        // A row of a busy aggregate sits in 'processing' throughout the test. A free row, with
        // no aggregate_id, must still reach 'processed' promptly: nothing about the busy
        // aggregate can hold it back, because it takes part in no aggregate's ordering. The busy
        // row needs a live lease: the relay reclaims stale claims on its first cycle, and a
        // 'processing' row with no lease_expires_at counts as stale, so an un-leased fixture
        // would revert to 'pending' almost immediately and never constrain anything.
        insertInboxMessage(
            source = "s",
            idempotencyKey = "busy",
            aggregateId = "agg-busy",
            state = "processing",
            consumption = "push",
            eventType = "evt"
        )
        renewInboxLease(source = "s", idempotencyKey = "busy")
        insertInboxMessage(
            source = "s",
            idempotencyKey = "free",
            aggregateId = null,
            consumption = "push",
            eventType = "evt"
        )
        startRelay()

        assertTrue(awaitUntil { getInboxMessage("s", "free")?.state == "processed" })
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
