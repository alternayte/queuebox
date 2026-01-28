package org.nxtspec.metrics

/**
 * Interface for collecting QueueBox metrics.
 * Implementations can record various metrics for outbox and inbox operations.
 */
interface MetricsCollectorInterface {

    // Outbox metrics

    /**
     * Record a successfully sent outbox message.
     */
    fun recordMessageSent()

    /**
     * Record a failed outbox message (will be retried).
     */
    fun recordMessageFailed()

    /**
     * Record a dead letter outbox message (max retries exceeded).
     */
    fun recordMessageDead()

    /**
     * Record processing duration in milliseconds.
     */
    fun recordProcessingDuration(durationMs: Long)

    /**
     * Record publish duration in milliseconds for a specific destination type.
     */
    fun recordPublishDuration(durationMs: Long, destinationType: String)

    /**
     * Update the pending message count.
     */
    fun updatePendingCount(count: Long)

    // Inbox metrics

    /**
     * Record a new inbox message received.
     */
    fun recordInboxReceived()

    /**
     * Record a duplicate inbox message detected.
     */
    fun recordInboxDuplicate()
}
