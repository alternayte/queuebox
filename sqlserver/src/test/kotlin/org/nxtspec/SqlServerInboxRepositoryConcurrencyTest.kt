package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-001 for SQL Server. Two concurrent claimers must never claim the same inbox message.
 */
@Tag("integration")
class SqlServerInboxRepositoryConcurrencyTest : SqlServerTestBase() {

    private lateinit var repository: SqlServerInboxRepository

    @BeforeEach
    fun setup() {
        repository = SqlServerInboxRepository()
    }

    @Test
    fun `two claimers never claim the same inbox message`() = runBlocking {
        repeat(100) { index ->
            insertInboxMessage(source = "stripe", idempotencyKey = "evt_$index")
        }

        val results = withContext(Dispatchers.IO) {
            val first = async { repository.claimPending(50) }
            val second = async { repository.claimPending(50) }
            listOf(first, second).awaitAll()
        }

        val firstIds = results[0].map { it.id }.toSet()
        val secondIds = results[1].map { it.id }.toSet()

        assertTrue(firstIds.intersect(secondIds).isEmpty(), "The two claimers must not overlap")
        assertEquals(100, (firstIds + secondIds).size, "Both claimers together must claim 100 rows")
    }

    @Test
    fun `claimPending records the claim time`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_1")

        repository.claimPending(10)

        assertEquals("processing", getInboxMessageState(id))
        assertTrue(getInboxClaimedAt(id) != null, "claimed_at must be set by the claim")
    }

    @Test
    fun `two claimers never hold the same aggregate at the same time`() = runBlocking {
        // Five messages for each of two aggregates. A claimer must hold at most one message
        // per aggregate, and the two claimers must not hold the same aggregate.
        listOf("agg-a", "agg-b").forEach { aggregate ->
            repeat(5) { index ->
                insertInboxMessage(
                    source = "stripe",
                    idempotencyKey = "$aggregate-$index",
                    aggregateId = aggregate
                )
            }
        }

        val results = withContext(Dispatchers.IO) {
            val first = async { repository.claimPending(10) }
            val second = async { repository.claimPending(10) }
            listOf(first, second).awaitAll()
        }

        val firstAggregates = results[0].mapNotNull { it.aggregateId }
        val secondAggregates = results[1].mapNotNull { it.aggregateId }

        assertEquals(
            firstAggregates.distinct().size,
            firstAggregates.size,
            "One claimer must hold at most one message per aggregate"
        )
        assertEquals(
            secondAggregates.distinct().size,
            secondAggregates.size,
            "One claimer must hold at most one message per aggregate"
        )
        assertTrue(
            firstAggregates.intersect(secondAggregates.toSet()).isEmpty(),
            "Two claimers must not hold the same aggregate"
        )
        assertTrue(
            (firstAggregates + secondAggregates).size <= 2,
            "At most one message per aggregate is in state 'processing'"
        )
    }

    @Test
    fun `claimPending releases the extra messages of one aggregate back to pending`() = runBlocking {
        repeat(3) { index ->
            insertInboxMessage(
                source = "stripe",
                idempotencyKey = "agg-$index",
                aggregateId = "agg-a"
            )
        }

        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size, "Only the oldest message of the aggregate stays claimed")
        assertEquals(1L, repository.countByState("processing"))
        assertEquals(2L, repository.countByState("pending"))
    }
}
