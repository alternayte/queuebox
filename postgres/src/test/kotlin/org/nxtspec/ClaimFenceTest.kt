package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Seventh review gate. A terminal write must only land when the caller still owns the claim.
 *
 * The reclaim step returns a row to state 'pending' on a timer, not on proof that the owner
 * died. The old owner therefore stays alive and completes the message. Every terminal write
 * matched on the primary key alone, so the old owner overwrote the state of the new owner.
 */
@Tag("integration")
class ClaimFenceTest : PostgresTestBase() {

    private lateinit var outboxRepository: OutboxRepository
    private lateinit var inboxRepository: InboxRepository

    @BeforeEach
    fun setup() {
        outboxRepository = OutboxRepository()
        inboxRepository = InboxRepository()
    }

    @Test
    fun `a stale owner cannot mark an outbox row sent after a reclaim and a new claim`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)
        setOutboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getOutboxClaimedAt(id)

        outboxRepository.reclaimStale(5.minutes)
        outboxRepository.claimBatch(1)

        val won = outboxRepository.markSent(id, staleClaim)

        assertFalse(won, "The stale owner must not win the terminal write.")

        assertEquals("processing", getOutboxMessageState(id))
    }

    @Test
    fun `a stale owner cannot schedule a retry for an outbox row that a new owner holds`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)
        setOutboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getOutboxClaimedAt(id)

        outboxRepository.reclaimStale(5.minutes)
        outboxRepository.claimBatch(1)

        val won = outboxRepository.scheduleRetry(id, 1_000, staleClaim, "stale")

        assertFalse(won, "The stale owner must not win the terminal write.")

        val (state, attempt) = getOutboxMessageStateAndAttempt(id)
        assertEquals("processing", state)
        assertEquals(0, attempt)
    }

    @Test
    fun `a stale owner cannot mark an outbox row dead after a reclaim and a new claim`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)
        setOutboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getOutboxClaimedAt(id)

        outboxRepository.reclaimStale(5.minutes)
        outboxRepository.claimBatch(1)

        val won = outboxRepository.markDead(id, staleClaim, "stale")

        assertFalse(won, "The stale owner must not win the terminal write.")

        assertEquals("processing", getOutboxMessageState(id))
    }

    @Test
    fun `a stale owner cannot mark an inbox row processed after a reclaim and a new claim`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_1")
        inboxRepository.claimPending(1)
        setInboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getInboxClaimedAt(id)

        inboxRepository.reclaimStale(5.minutes)
        inboxRepository.claimPending(1)

        val won = inboxRepository.markProcessed(id, staleClaim)

        assertFalse(won, "The stale owner must not win the terminal write.")

        assertEquals("processing", getInboxMessageState(id))
    }

    @Test
    fun `a stale owner cannot mark an inbox row dead after a reclaim and a new claim`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_2")
        inboxRepository.claimPending(1)
        setInboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getInboxClaimedAt(id)

        inboxRepository.reclaimStale(5.minutes)
        inboxRepository.claimPending(1)

        val won = inboxRepository.markDead(id, staleClaim)

        assertFalse(won, "The stale owner must not win the terminal write.")

        assertEquals("processing", getInboxMessageState(id))
    }

    @Test
    fun `a stale owner cannot mark an outbox row sent while the row waits in pending`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)
        setOutboxClaimedAt(id, Clock.System.now() - 10.minutes)
        val staleClaim = getOutboxClaimedAt(id)

        outboxRepository.reclaimStale(5.minutes)

        val won = outboxRepository.markSent(id, staleClaim)

        assertFalse(won, "The stale owner must not win the terminal write.")

        assertEquals("pending", getOutboxMessageState(id))
    }

    @Test
    fun `the owner of a claim still marks an outbox row sent`() = runBlocking {
        val id = insertOutboxMessage(state = "pending")
        outboxRepository.claimBatch(1)
        val claim = getOutboxClaimedAt(id)

        val won = outboxRepository.markSent(id, claim)

        assertTrue(won, "The owner of the claim must win the terminal write.")

        assertEquals("sent", getOutboxMessageState(id))
    }

    @Test
    fun `the owner of a claim still marks an inbox row processed`() = runBlocking {
        val id = insertInboxMessage(source = "stripe", idempotencyKey = "evt_3")
        inboxRepository.claimPending(1)
        val claim = getInboxClaimedAt(id)

        val won = inboxRepository.markProcessed(id, claim)

        assertTrue(won, "The owner of the claim must win the terminal write.")

        assertEquals("processed", getInboxMessageState(id))
    }
}
