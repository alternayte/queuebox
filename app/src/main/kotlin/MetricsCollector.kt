package org.nxtspec.app

import io.micrometer.core.instrument.MeterRegistry
import org.nxtspec.metrics.InboxRejectionReason
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
     * Record messages that the reclaim step returned to state 'pending'.
     */
    override fun recordMessageReclaimed(count: Int) {
        if (count > 0) metrics.outboxMessagesReclaimed.increment(count.toDouble())
    }

    /**
     * Record an error that stopped the processing of one outbox message.
     */
    override fun recordProcessError() {
        metrics.outboxProcessErrors.increment()
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
     * Record an inbox message that the relay forwarded to the outbox.
     */
    override fun recordInboxForwarded() {
        metrics.inboxMessagesForwarded.increment()
    }

    /**
     * Record an inbox relay error.
     */
    override fun recordInboxRelayError() {
        metrics.inboxRelayErrors.increment()
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

    /**
     * Record one message that reached the destination.
     */
    override fun recordDestinationSuccess(destination: String) {
        metrics.recordDestinationSuccess(destination)
    }

    /**
     * Record one message that the destination did not accept.
     */
    override fun recordDestinationFailure(destination: String) {
        metrics.recordDestinationFailure(destination)
    }

    /**
     * Update the number of messages that wait for a publish to one destination.
     */
    override fun updateQueueDepth(destination: String, depth: Long) {
        metrics.setQueueDepth(destination, depth)
    }

    /**
     * Record one transform failure under the configured error strategy.
     */
    override fun recordTransformFailure(strategy: String) {
        metrics.recordTransformFailure(strategy)
    }

    /**
     * Record one inbox message that QueueBox did not accept.
     */
    override fun recordInboxRejection(reason: InboxRejectionReason) {
        metrics.recordInboxRejection(reason)
    }

    /**
     * Record one HTTP publish response under its status class.
     */
    override fun recordHttpStatus(statusCode: Int) {
        metrics.recordHttpStatus(statusCode)
    }
}
