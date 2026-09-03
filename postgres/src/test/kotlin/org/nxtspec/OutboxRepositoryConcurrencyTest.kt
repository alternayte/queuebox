package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers F-009. The outbox claim must return the oldest messages first, and two claimers must
 * not block each other.
 */
@Tag("integration")
class OutboxRepositoryConcurrencyTest : PostgresTestBase() {

    private lateinit var repository: OutboxRepository

    @BeforeEach
    fun setup() {
        repository = OutboxRepository()
    }

    @Test
    fun `claimBatch returns the oldest scheduled messages in order`() = runBlocking {
        // Insert in reverse order, so the physical row order differs from the schedule order.
        // Without ORDER BY the claim returns the newest rows first.
        val base = Clock.System.now() - (100).seconds
        val idsNewestFirst = (9 downTo 0).map { index ->
            insertOutboxMessage(state = "pending", scheduledAt = base + index.seconds)
        }
        val idsOldestFirst = idsNewestFirst.reversed()

        val claimed = repository.claimBatch(3)

        assertEquals(idsOldestFirst.take(3), claimed.map { it.id })
    }

    @Test
    fun `two claimers never claim the same message`() = runBlocking {
        repeat(100) { insertOutboxMessage(state = "pending") }

        val results = withContext(Dispatchers.IO) {
            val first = async { timed { repository.claimBatch(50) } }
            val second = async { timed { repository.claimBatch(50) } }
            listOf(first, second).awaitAll()
        }

        val firstIds = results[0].second.map { it.id }.toSet()
        val secondIds = results[1].second.map { it.id }.toSet()

        assertTrue(firstIds.intersect(secondIds).isEmpty(), "The two claimers must not overlap")
        assertEquals(100, (firstIds + secondIds).size, "Both claimers together must claim 100 rows")
        results.forEach { (elapsed, _) ->
            assertTrue(elapsed < 1000, "A claimer must not block for more than one second. Took ${elapsed}ms")
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<Long, T> {
        val start = System.currentTimeMillis()
        val result = block()
        return (System.currentTimeMillis() - start) to result
    }

    @Test
    fun `claimBatch records the claim time`() = runBlocking {
        val id: UUID = insertOutboxMessage(state = "pending")

        repository.claimBatch(1)

        assertEquals("processing", getOutboxMessageState(id))
        assertTrue(getOutboxClaimedAt(id) != null, "claimed_at must be set by the claim")
    }
}
