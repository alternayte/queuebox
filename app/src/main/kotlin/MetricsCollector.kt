package org.nxtspec.app

import io.micrometer.core.instrument.MeterRegistry
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.metrics.QueueBoxMetrics

/**
 * Metrics collector that wraps QueueBoxMetrics for convenient metric recording.
 */
class MetricsCollector(registry: MeterRegistry) : MetricsCollectorInterface {
    private val metrics = QueueBoxMetrics(registry)

    /**
     * Record a successfully sent outbox message.
     */
    override fun recordMessageSent() {
        metrics.outboxMessagesSent.increment()
    }

    /**
     * Record a failed outbox message.
     */
    override fun recordMessageFailed() {
        metrics.outboxMessagesFailed.increment()
    }

    /**
     * Record a dead letter outbox message (max retries exceeded).
     */
    override fun recordMessageDead() {
        metrics.outboxMessagesDead.increment()
    }

    /**
     * Record processing duration in milliseconds.
     */
    override fun recordProcessingDuration(durationMs: Long) {
        metrics.recordProcessingDuration(durationMs)
    }

    /**
     * Record publish duration in milliseconds for a specific destination type.
     */
    override fun recordPublishDuration(durationMs: Long, destinationType: String) {
        metrics.recordPublishDuration(durationMs, destinationType)
    }

    /**
     * Record a new inbox message received.
     */
    override fun recordInboxReceived() {
        metrics.inboxMessagesNew.increment()
    }

    /**
     * Record a duplicate inbox message detected.
     */
    override fun recordInboxDuplicate() {
        metrics.inboxMessagesDuplicate.increment()
    }

    /**
     * Update the pending message count.
     */
    override fun updatePendingCount(count: Long) {
        metrics.setPendingMessageCount(count)
    }

    /**
     * Record a retention cleanup run.
     */
    override fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long) {
        metrics.recordCleanupRun(table, deleted, durationNanos)
    }
}
