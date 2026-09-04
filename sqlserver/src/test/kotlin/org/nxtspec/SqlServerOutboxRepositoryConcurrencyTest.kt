package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers F-009 on SQL Server. The outbox claim must return the oldest messages first, and the
 * claim must record the claim time. `SqlServerOutboxRepositoryTest` covers the non-overlap of
 * two concurrent claimers.
 */
class SqlServerOutboxRepositoryConcurrencyTest : SqlServerTestBase() {

    private val repository = SqlServerOutboxRepository()

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
    fun `claimBatch records the claim time`() = runBlocking {
        val id: UUID = insertOutboxMessage(state = "pending")

        repository.claimBatch(1)

        assertEquals("processing", getOutboxMessageState(id))
        assertTrue(getOutboxClaimedAt(id) != null, "claimed_at must be set by the claim")
    }
}
