package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Covers F-006 on SQL Server. A message that stays in state 'processing' after a crash must
 * return to state 'pending'. This test is the twin of the PostgreSQL `ReclaimStaleTest`.
 */
class SqlServerReclaimStaleTest : SqlServerTestBase() {

    private val outboxRepository = SqlServerOutboxRepository()
    private val inboxRepository = SqlServerInboxRepository()

    @Test
    fun `reclaimStale returns a stale outbox claim to pending and keeps the attempt count`() = runBlocking {
        val id = insertOutboxMessage(state = "pending", attempt = 2)
        outboxRepository.claimBatch(1)
        setOutboxClaimedAt(id, Clock.System.now() - 10.minutes)

        val reclaimed = outboxRepository.reclaimStale(5.minutes)

        assertEquals(1, reclaimed)
        val (state, attempt) = getOutboxMessageStateAndAttempt(id)
        assertEquals("pending", state)
        assertEquals(2, attempt)
        assertNull(getOutboxClaimedAt(id))
    }

    @Test
    fun `reclaimStale leaves a fresh outbox claim alone`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)

        val reclaimed = outboxRepository.reclaimStale(5.minutes)

        assertEquals(0, reclaimed)
        assertEquals("processing", getOutboxMessageState(id))
    }

    @Test
    fun `reclaimStale returns a stale inbox claim to pending`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_1")
        inboxRepository.claimPending(1)
        setInboxClaimedAt(id, Clock.System.now() - 10.minutes)

        val reclaimed = inboxRepository.reclaimStale(5.minutes)

        assertEquals(1, reclaimed)
        assertEquals("pending", getInboxMessageState(id))
        assertNull(getInboxClaimedAt(id))
    }

    @Test
    fun `reclaimStale leaves a fresh inbox claim alone`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_2")
        inboxRepository.claimPending(1)

        val reclaimed = inboxRepository.reclaimStale(5.minutes)

        assertEquals(0, reclaimed)
        assertEquals("processing", getInboxMessageState(id))
    }
}
