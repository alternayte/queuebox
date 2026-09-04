package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Covers F-007 and F-008.
 *
 * F-008: the retention cleanup must delete in batches of at most `batchSize` rows.
 * F-007: the inbox cleanup must use the inbox completed states, not the outbox states.
 */
class RetentionBatchingTest {

    private data class Row(val id: UUID, val state: String, val timestamp: Instant)

    private class FakeOutboxRepository(rows: List<Row>) : OutboxRepositoryInterface {
        val rows = rows.toMutableList()
        val deleteCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = emptyList()
        override suspend fun insert(message: OutboxMessage) = Unit
        override suspend fun markSent(id: UUID) = Unit
        override suspend fun scheduleRetry(id: UUID, delayMs: Long, error: String?) = Unit
        override suspend fun markDead(id: UUID, error: String?) = Unit
        override suspend fun countByState(state: String): Long = rows.count { it.state == state }.toLong()

        override suspend fun reclaimStale(olderThan: Duration): Int = 0

        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int {
            val eligible = rows.filter { it.state == state && it.timestamp < cutoff }.take(limit)
            rows.removeAll(eligible)
            deleteCalls.add(state to eligible.size)
            return eligible.size
        }

        override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int {
            val ordered = rows.filter { it.state == state }.sortedByDescending { it.timestamp }
            val eligible = ordered.drop(keepCount).take(limit)
            rows.removeAll(eligible)
            deleteCalls.add(state to eligible.size)
            return eligible.size
        }
    }

    private class FakeInboxRepository(rows: List<Row>) : InboxRepositoryInterface {
        val rows = rows.toMutableList()
        val deleteCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun store(message: InboxMessage): InboxResult = InboxResult.Stored

        override suspend fun storeDead(message: InboxMessage): InboxResult = InboxResult.Stored

        override suspend fun claimPending(batchSize: Int): List<InboxMessage> = emptyList()
        override suspend fun markProcessed(id: UUID) = Unit
        override suspend fun markDead(id: UUID) = Unit
        override suspend fun markDeadByKey(source: String, idempotencyKey: String) = Unit
        override suspend fun countByState(state: String): Long = rows.count { it.state == state }.toLong()

        override suspend fun reclaimStale(olderThan: Duration): Int = 0

        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int {
            val eligible = rows.filter { it.state == state && it.timestamp < cutoff }.take(limit)
            rows.removeAll(eligible)
            deleteCalls.add(state to eligible.size)
            return eligible.size
        }
    }

    private class RecordingMetrics : MetricsCollectorInterface {
        val cleanupRuns = mutableListOf<Pair<String, Int>>()
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
        override fun recordInboxForwarded() = Unit
        override fun recordInboxRelayError() = Unit
        override fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long) {
            cleanupRuns.add(table to deleted)
        }
    }

    private fun oldRows(count: Int, state: String): List<Row> {
        val old = Clock.System.now() - kotlin.time.Duration.parse("30d")
        return (1..count).map { Row(UUID.randomUUID(), state, old) }
    }

    @Test
    fun `F-008 outbox age cleanup deletes in batches of at most batchSize`() = runBlocking {
        val outbox = FakeOutboxRepository(oldRows(250, "sent"))
        val inbox = FakeInboxRepository(emptyList())
        val metrics = RecordingMetrics()
        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "7d",
                cleanupInterval = "1h",
                batchSize = 100
            ),
            inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
        )

        val service = RetentionService(config, outbox, inbox, metrics)
        val deleted = service.runOutboxCleanupOnce()

        val sentCalls = outbox.deleteCalls.filter { it.first == "sent" }
        assertEquals(3, sentCalls.size, "Expected three delete calls for state 'sent'")
        assertTrue(sentCalls.all { it.second <= 100 }, "Every delete call must return at most 100")
        assertEquals(250, deleted)
        assertEquals(0, outbox.rows.size, "Every eligible row must be gone")
    }

    @Test
    fun `F-007 inbox age cleanup deletes processed rows and reports them`() = runBlocking {
        val outbox = FakeOutboxRepository(emptyList())
        val inbox = FakeInboxRepository(oldRows(5, "processed"))
        val metrics = RecordingMetrics()
        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
            inbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "7d",
                cleanupInterval = "1h",
                batchSize = 100
            )
        )

        val service = RetentionService(config, outbox, inbox, metrics)
        val deleted = service.runInboxCleanupOnce()

        val states = inbox.deleteCalls.map { it.first }.distinct()
        assertTrue("processed" in states, "The inbox cleanup must use the inbox states")
        assertTrue("sent" !in states, "The inbox cleanup must not use the outbox states")
        assertEquals(5, deleted)
        assertEquals(0, inbox.rows.size, "Every processed row older than the cutoff must be gone")
        assertEquals(listOf("inbox" to 5), metrics.cleanupRuns)
    }

    @Test
    fun `F-008 outbox count cleanup deletes in batches of at most batchSize`() = runBlocking {
        val outbox = FakeOutboxRepository(oldRows(250, "sent"))
        val inbox = FakeInboxRepository(emptyList())
        val metrics = RecordingMetrics()
        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(
                policy = RetentionPolicy.COUNT,
                maxCount = 50,
                cleanupInterval = "1h",
                batchSize = 100
            ),
            inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
        )

        val service = RetentionService(config, outbox, inbox, metrics)
        val deleted = service.runOutboxCleanupOnce()

        val sentCalls = outbox.deleteCalls.filter { it.first == "sent" }
        assertTrue(sentCalls.all { it.second <= 100 }, "Every delete call must return at most 100")
        assertEquals(200, deleted)
        assertEquals(50, outbox.rows.size, "The most recent 50 rows must remain")
    }
}
