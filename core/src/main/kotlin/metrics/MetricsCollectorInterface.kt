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
     * Record messages that the reclaim step returned to state 'pending'.
     */
    fun recordMessageReclaimed(count: Int)

    /**
     * Record an error that stopped the processing of one outbox message.
     */
    fun recordProcessError()

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

    /**
     * Record an inbox message that the relay forwarded to the outbox.
     */
    fun recordInboxForwarded()

    /**
     * Record an inbox relay error.
     */
    fun recordInboxRelayError()

    // Cleanup metrics

    /**
     * Record a retention cleanup run.
     * @param table The table cleaned (e.g., "outbox" or "inbox")
     * @param deleted Number of records deleted
     * @param durationNanos Duration of the cleanup in nanoseconds
     */
    fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long)
}
