package org.nxtspec.app

import io.micrometer.core.instrument.MeterRegistry
import org.nxtspec.metrics.QueueBoxMetrics

/**
 * Metrics collector that wraps QueueBoxMetrics for convenient metric recording.
 */
class MetricsCollector(registry: MeterRegistry) {
    private val metrics = QueueBoxMetrics(registry)

    /**
     * Record a successfully sent outbox message.
     */
    fun recordMessageSent() {
        metrics.outboxMessagesSent.increment()
    }

    /**
     * Record a failed outbox message.
     */
    fun recordMessageFailed() {
        metrics.outboxMessagesFailed.increment()
    }

    /**
     * Record a dead letter outbox message (max retries exceeded).
     */
    fun recordMessageDead() {
        metrics.outboxMessagesDead.increment()
    }

    /**
     * Record processing duration in milliseconds.
     */
    fun recordProcessingDuration(durationMs: Long) {
        metrics.recordProcessingDuration(durationMs)
    }

    /**
     * Record publish duration in milliseconds for a specific destination type.
     */
    fun recordPublishDuration(durationMs: Long, destinationType: String) {
        metrics.recordPublishDuration(durationMs, destinationType)
    }

    /**
     * Record a new inbox message received.
     */
    fun recordInboxReceived() {
        metrics.inboxMessagesNew.increment()
    }

    /**
     * Record a duplicate inbox message detected.
     */
    fun recordInboxDuplicate() {
        metrics.inboxMessagesDuplicate.increment()
    }

    /**
     * Update the pending message count.
     */
    fun updatePendingCount(count: Long) {
        metrics.setPendingMessageCount(count)
    }
}
