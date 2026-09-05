package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.TransactionRunner
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * Seventh review gate. The relay must observe a lost claim and must not forward the message.
 *
 * The reclaim step returns a claimed inbox row to state 'pending' on a timer. The old owner
 * stays alive, so two replicas can hold the same row. Each forward creates a new outbox
 * identifier, so the two copies carry a different `X-Message-Id`. A consumer that deduplicates
 * on that header cannot see the duplicate. The relay must therefore roll back the forward when
 * the mark loses the claim.
 */
class InboxRelayClaimLostTest {

    private class LostClaimInboxRepository(private val pending: MutableList<InboxMessage>) :
        InboxRepositoryInterface {
        override suspend fun store(message: InboxMessage): InboxResult = InboxResult.Stored
        override suspend fun storeDead(message: InboxMessage): InboxResult = InboxResult.Stored

        override suspend fun claimPending(batchSize: Int): List<InboxMessage> {
            val claimed = pending.take(batchSize)
            pending.removeAll(claimed)
            return claimed
        }

        // Another replica owns the row now, so the mark writes no row.
        override suspend fun markProcessed(id: UUID, claimedAt: Instant?): Boolean = false

        override suspend fun markDead(id: UUID, claimedAt: Instant?): Boolean = false

        override suspend fun countByState(state: String): Long = 0
        override suspend fun reclaimStale(olderThan: Duration): Int = 0
        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = 0
    }

    private class RecordingOutboxRepository : OutboxRepositoryInterface {
        val inserted = mutableListOf<OutboxMessage>()
        override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = emptyList()
        override suspend fun insert(message: OutboxMessage) {
            inserted.add(message)
        }

        override suspend fun markSent(id: UUID, claimedAt: Instant?): Boolean = true
        override suspend fun scheduleRetry(id: UUID, delayMs: Long, claimedAt: Instant?, error: String?): Boolean = true
        override suspend fun markDead(id: UUID, claimedAt: Instant?, error: String?): Boolean = true
        override suspend fun countByState(state: String): Long = 0
        override suspend fun reclaimStale(olderThan: Duration): Int = 0
        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = 0
        override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int = 0
    }

    /** Rolls the recorded work back when the block throws, like a real transaction. */
    private class RollbackTransactionRunner(private val outbox: RecordingOutboxRepository) : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            val mark = outbox.inserted.size
            return try {
                block()
            } catch (e: Throwable) {
                while (outbox.inserted.size > mark) outbox.inserted.removeAt(outbox.inserted.size - 1)
                throw e
            }
        }
    }

    private class RecordingMetrics : MetricsCollectorInterface {
        var forwarded = 0
        var claimsLost = 0
        override fun recordMessageSent() = Unit
        override fun recordMessageFailed() = Unit
        override fun recordMessageDead() = Unit
        override fun recordMessageReclaimed(count: Int) = Unit
        override fun recordProcessError() = Unit
        override fun recordProcessingDuration(durationMs: Long) = Unit
        override fun recordPublishDuration(durationMs: Long, destinationType: String) = Unit
        override fun updatePendingCount(count: Long) = Unit
        override fun recordInboxReceived() = Unit
        override fun recordInboxDuplicate() = Unit
        override fun recordInboxForwarded() {
            forwarded++
        }

        override fun recordInboxRelayError() = Unit
        override fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long) = Unit
        override fun recordClaimLost(component: String) {
            claimsLost++
        }
    }

    private fun inboxMessage() = InboxMessage(
        id = UUID.randomUUID(),
        source = "stripe",
        idempotencyKey = "evt_1",
        aggregateId = "cus_1",
        eventType = "payment.succeeded",
        payload = JsonObject(mapOf("amount" to JsonPrimitive(100)))
    )

    @Test
    fun `a lost claim stops the forward and leaves no outbox row`() = runBlocking {
        val inbox = LostClaimInboxRepository(mutableListOf(inboxMessage()))
        val outbox = RecordingOutboxRepository()
        val metrics = RecordingMetrics()

        val relay = InboxRelay(
            config = InboxRelayConfig(),
            inboxRepository = inbox,
            outboxRepository = outbox,
            transactionRunner = RollbackTransactionRunner(outbox),
            metricsCollector = metrics
        )

        val forwarded = relay.relayBatch()

        assertEquals(0, forwarded, "The relay must not report a forward that it lost.")
        assertEquals(0, outbox.inserted.size, "A lost claim must leave no outbox row behind.")
        assertEquals(0, metrics.forwarded, "A lost claim is not a forward.")
        assertEquals(1, metrics.claimsLost, "The relay must record the lost claim.")
    }

    @Test
    fun `a lost claim on the dead path changes no state`() = runBlocking {
        // The topic template renders empty, so the relay marks the row dead. Another replica
        // owns the row, so the mark writes no row.
        val inbox = LostClaimInboxRepository(mutableListOf(inboxMessage()))
        val outbox = RecordingOutboxRepository()
        val metrics = RecordingMetrics()

        val relay = InboxRelay(
            config = InboxRelayConfig(),
            inboxRepository = inbox,
            outboxRepository = outbox,
            transactionRunner = RollbackTransactionRunner(outbox),
            sourceTopicTemplates = mapOf("stripe" to "   "),
            metricsCollector = metrics
        )

        val forwarded = relay.relayBatch()

        assertEquals(0, forwarded)
        assertEquals(0, outbox.inserted.size)
        assertEquals(1, metrics.claimsLost, "The relay must record the lost claim.")
    }
}
