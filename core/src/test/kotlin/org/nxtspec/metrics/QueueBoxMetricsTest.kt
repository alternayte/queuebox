package org.nxtspec.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the metric definitions that QueueBox publishes.
 */
class QueueBoxMetricsTest {

    private fun metrics() = SimpleMeterRegistry().let { it to QueueBoxMetrics(it) }

    @Test
    fun `registers every outbox counter`() {
        val (registry, metrics) = metrics()

        metrics.outboxMessagesSent.increment()
        metrics.outboxMessagesFailed.increment(2.0)
        metrics.outboxMessagesDead.increment(3.0)
        metrics.outboxProcessErrors.increment(4.0)
        metrics.outboxMessagesReclaimed.increment(5.0)

        assertEquals(1.0, metrics.outboxMessagesSent.count())
        assertEquals(2.0, metrics.outboxMessagesFailed.count())
        assertEquals(3.0, metrics.outboxMessagesDead.count())
        assertEquals(4.0, metrics.outboxProcessErrors.count())
        assertEquals(5.0, metrics.outboxMessagesReclaimed.count())
        assertNotNull(registry.find("queuebox_outbox_messages_total").counter())
    }

    @Test
    fun `registers every inbox counter`() {
        val (_, metrics) = metrics()

        metrics.inboxMessagesNew.increment()
        metrics.inboxMessagesDuplicate.increment()
        metrics.inboxMessagesForwarded.increment()
        metrics.inboxRelayErrors.increment()

        assertEquals(1.0, metrics.inboxMessagesNew.count())
        assertEquals(1.0, metrics.inboxMessagesDuplicate.count())
        assertEquals(1.0, metrics.inboxMessagesForwarded.count())
        assertEquals(1.0, metrics.inboxRelayErrors.count())
    }

    @Test
    fun `pending gauge follows the last set value`() {
        val (_, metrics) = metrics()

        metrics.setPendingMessageCount(42)

        assertEquals(42.0, metrics.outboxMessagesPending.value())
    }

    @Test
    fun `records the processing duration`() {
        val (_, metrics) = metrics()

        metrics.recordProcessingDuration(120)

        assertEquals(1L, metrics.outboxProcessingDuration.count())
    }

    @Test
    fun `creates one publish timer per destination type`() {
        val (registry, metrics) = metrics()

        metrics.recordPublishDuration(10, "http")
        metrics.recordPublishDuration(20, "http")
        metrics.recordPublishDuration(30, "rabbitmq")

        assertEquals(2L, metrics.getPublishTimer("http").count())
        assertEquals(1L, metrics.getPublishTimer("rabbitmq").count())
        assertEquals(
            2,
            registry.find("queuebox_outbox_publish_duration_seconds").timers().size
        )
    }

    @Test
    fun `records a cleanup run per table`() {
        val (registry, metrics) = metrics()

        metrics.recordCleanupRun("outbox", 5, 1_000_000)
        metrics.recordCleanupRun("inbox", 3, 2_000_000)
        metrics.recordCleanupRun("outbox", 2, 3_000_000)

        val outboxCounter = registry.find("queuebox_cleanup_messages_deleted_total")
            .tag("table", "outbox").counter()
        val inboxCounter = registry.find("queuebox_cleanup_messages_deleted_total")
            .tag("table", "inbox").counter()

        assertEquals(7.0, outboxCounter!!.count())
        assertEquals(3.0, inboxCounter!!.count())
        assertEquals(
            2L,
            registry.find("queuebox_cleanup_duration_seconds").tag("table", "outbox").timer()!!.count()
        )
        assertTrue(
            registry.find("queuebox_cleanup_last_run_timestamp").tag("table", "outbox").gauge()!!.value() > 0
        )
    }

    @Test
    fun `publishes the uptime and info gauges`() {
        val (_, metrics) = metrics()

        assertTrue(metrics.uptime.value() >= 0.0)
        assertEquals(1.0, metrics.info.value())
    }
}
