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
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Covers F-002. The relay moves a stored inbox message into the outbox table with a fixed
 * field mapping.
 */
class InboxRelayTest {

    private class FakeInboxRepository(private val pending: MutableList<InboxMessage>) :
        InboxRepositoryInterface {
        val processed = mutableListOf<UUID>()
        val dead = mutableListOf<UUID>()
        var reclaimCalls = 0

        override suspend fun store(message: InboxMessage): InboxResult = InboxResult.Stored

        override suspend fun storeDead(message: InboxMessage): InboxResult = InboxResult.Stored

        override suspend fun claimPending(batchSize: Int): List<InboxMessage> {
            val claimed = pending.take(batchSize)
            pending.removeAll(claimed)
            return claimed
        }

        override suspend fun markProcessed(id: UUID, claimedAt: Instant?): Boolean {
            processed.add(id)
            return true
        }

        override suspend fun markDead(id: UUID, claimedAt: Instant?): Boolean {
            dead.add(id)
            return true
        }

        override suspend fun countByState(state: String): Long = 0
        override suspend fun reclaimStale(olderThan: Duration): Int {
            reclaimCalls++
            return 0
        }

        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = 0
    }

    private class FakeOutboxRepository(private val failInsert: Boolean = false) : OutboxRepositoryInterface {
        val inserted = mutableListOf<OutboxMessage>()

        override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = emptyList()

        override suspend fun insert(message: OutboxMessage) {
            if (failInsert) error("insert failed")
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

    private class DirectTransactionRunner : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class RecordingMetrics : MetricsCollectorInterface {
        var forwarded = 0
        var relayErrors = 0
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

        override fun recordInboxRelayError() {
            relayErrors++
        }

        override fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long) = Unit
    }

    private fun inboxMessage(
        source: String = "stripe",
        eventType: String? = "payment.succeeded",
        aggregateId: String? = "cus_1"
    ) = InboxMessage(
        id = UUID.randomUUID(),
        source = source,
        idempotencyKey = "evt_1",
        aggregateId = aggregateId,
        eventType = eventType,
        payload = JsonObject(mapOf("amount" to JsonPrimitive(100)))
    )

    private fun relay(
        inbox: FakeInboxRepository,
        outbox: FakeOutboxRepository,
        metrics: MetricsCollectorInterface? = null,
        topics: Map<String, String> = emptyMap(),
        config: InboxRelayConfig = InboxRelayConfig()
    ) = InboxRelay(
        config = config,
        inboxRepository = inbox,
        outboxRepository = outbox,
        transactionRunner = DirectTransactionRunner(),
        sourceTopicTemplates = topics,
        metricsCollector = metrics
    )

    @Test
    fun `forwards an inbox message with the fixed field mapping`() = runBlocking {
        val message = inboxMessage()
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository()
        val metrics = RecordingMetrics()

        val forwarded = relay(inbox, outbox, metrics).relayBatch()

        assertEquals(1, forwarded)
        assertEquals(1, outbox.inserted.size)

        val row = outbox.inserted.single()
        assertEquals("payment.succeeded", row.topic)
        assertEquals("cus_1", row.key)
        assertEquals(message.payload, row.payload)
        assertEquals(message.id.toString(), row.headers["x-inbox-id"])
        assertEquals("stripe", row.headers["x-source"])
        assertEquals("evt_1", row.headers["x-idempotency-key"])
        assertEquals(listOf(message.id), inbox.processed)
        assertEquals(1, metrics.forwarded)
    }

    @Test
    fun `stamps the configured dead-letter ceiling on the relayed row`() = runBlocking {
        val message = inboxMessage()
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository()

        relay(inbox, outbox, config = InboxRelayConfig(maxAttempts = 1)).relayBatch()

        assertEquals(1, outbox.inserted.single().maxAttempts)
    }

    @Test
    fun `the default topic of a RabbitMQ source keeps a message with no event type`() = runBlocking {
        // Fifth review gate. The default must never destroy a message. An AMQP publisher that
        // sets no event type is normal, so the default template must not need one.
        val source = SourceConfig.RabbitMQ(
            queueName = "orders",
            connectionUrl = "amqp://guest:guest@localhost:5672"
        )
        val message = inboxMessage(source = "orders-queue", eventType = null)
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository()

        val forwarded = relay(
            inbox,
            outbox,
            topics = mapOf("orders-queue" to source.topic)
        ).relayBatch()

        assertEquals(1, forwarded, "The message must reach the outbox.")
        assertEquals(0, inbox.dead.size, "The message must not become dead.")
        assertEquals("orders-queue", outbox.inserted.single().topic)
    }

    @Test
    fun `renders the configured source topic template`() = runBlocking {
        val message = inboxMessage()
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository()

        relay(inbox, outbox, topics = mapOf("stripe" to "{{ source }}.{{ eventType }}")).relayBatch()

        assertEquals("stripe.payment.succeeded", outbox.inserted.single().topic)
    }

    @Test
    fun `marks the message dead when the topic template renders empty`() = runBlocking {
        val message = inboxMessage(eventType = null)
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository()
        val metrics = RecordingMetrics()

        val forwarded = relay(inbox, outbox, metrics).relayBatch()

        assertEquals(0, forwarded)
        assertTrue(outbox.inserted.isEmpty())
        assertEquals(listOf(message.id), inbox.dead)
        assertEquals(1, metrics.relayErrors)
    }

    @Test
    fun `leaves the inbox row recoverable when the outbox insert fails`() = runBlocking {
        val message = inboxMessage()
        val inbox = FakeInboxRepository(mutableListOf(message))
        val outbox = FakeOutboxRepository(failInsert = true)
        val metrics = RecordingMetrics()

        val forwarded = relay(inbox, outbox, metrics).relayBatch()

        assertEquals(0, forwarded)
        assertTrue(inbox.processed.isEmpty(), "The row must not be marked processed")
        assertTrue(inbox.dead.isEmpty(), "The row must stay recoverable, not dead")
        assertEquals(1, metrics.relayErrors)
    }

    @Test
    fun `reclaims stale claims on the first cycle`() = runBlocking {
        val inbox = FakeInboxRepository(mutableListOf())
        val outbox = FakeOutboxRepository()

        relay(inbox, outbox).relayBatch()

        assertEquals(1, inbox.reclaimCalls)
    }
}
