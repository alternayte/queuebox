package org.nxtspec.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.nxtspec.BuildInfo
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

    // --- F-053: the info tag carries the Gradle project version ---

    @Test
    fun `info gauge carries the gradle project version`() {
        val (registry, _) = metrics()

        val expected = System.getProperty("queuebox.version")
        assertNotNull(expected, "The build must pass the queuebox.version system property.")

        val gauge = registry.find("queuebox_info").gauge()
        assertNotNull(gauge)
        assertEquals(expected, gauge.id.getTag("version"))
    }

    @Test
    fun `build info reads the generated version`() {
        assertEquals(System.getProperty("queuebox.version"), BuildInfo.version)
    }

    // --- F-052: the metric gaps ---

    @Test
    fun `counts destination outcomes by destination and outcome`() {
        val (registry, metrics) = metrics()

        metrics.recordDestinationSuccess("orders-api")
        metrics.recordDestinationSuccess("orders-api")
        metrics.recordDestinationFailure("orders-api")
        metrics.recordDestinationFailure("billing-queue")

        assertEquals(
            2.0,
            registry.find("queuebox_outbox_destination_messages_total")
                .tag("destination", "orders-api").tag("outcome", "success").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_outbox_destination_messages_total")
                .tag("destination", "orders-api").tag("outcome", "failure").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_outbox_destination_messages_total")
                .tag("destination", "billing-queue").tag("outcome", "failure").counter()!!.count()
        )
    }

    @Test
    fun `counts transform failures by strategy`() {
        val (registry, metrics) = metrics()

        metrics.recordTransformFailure("fail")
        metrics.recordTransformFailure("fail")
        metrics.recordTransformFailure("dead")

        assertEquals(
            2.0,
            registry.find("queuebox_transform_failures_total").tag("strategy", "fail").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_transform_failures_total").tag("strategy", "dead").counter()!!.count()
        )
    }

    @Test
    fun `counts inbox rejections by a fixed reason`() {
        val (registry, metrics) = metrics()

        metrics.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED)
        metrics.recordInboxRejection(InboxRejectionReason.STORAGE_FAILED)

        assertEquals(
            1.0,
            registry.find("queuebox_inbox_rejections_total")
                .tag("reason", "extraction_failed").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_inbox_rejections_total")
                .tag("reason", "storage_failed").counter()!!.count()
        )
    }

    @Test
    fun `counts http responses by status class`() {
        val (registry, metrics) = metrics()

        metrics.recordHttpStatus(200)
        metrics.recordHttpStatus(204)
        metrics.recordHttpStatus(404)
        metrics.recordHttpStatus(503)
        metrics.recordHttpStatus(99)

        assertEquals(
            2.0,
            registry.find("queuebox_http_publish_responses_total").tag("status_class", "2xx").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_http_publish_responses_total").tag("status_class", "4xx").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_http_publish_responses_total").tag("status_class", "5xx").counter()!!.count()
        )
        assertEquals(
            1.0,
            registry.find("queuebox_http_publish_responses_total").tag("status_class", "other").counter()!!.count()
        )
    }

    @Test
    fun `reports the queue depth per destination`() {
        val (registry, metrics) = metrics()

        metrics.setQueueDepth("orders-api", 7)
        metrics.setQueueDepth("billing-queue", 2)
        metrics.setQueueDepth("orders-api", 3)

        assertEquals(
            3.0,
            registry.find("queuebox_outbox_queue_depth").tag("destination", "orders-api").gauge()!!.value()
        )
        assertEquals(
            2.0,
            registry.find("queuebox_outbox_queue_depth").tag("destination", "billing-queue").gauge()!!.value()
        )
    }
}
