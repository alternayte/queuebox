package org.nxtspec.metrics

/**
 * The reasons that QueueBox rejects an inbox message.
 *
 * F-052: the reason is a fixed enumeration. A free text reason would make the label set
 * unbounded.
 */
enum class InboxRejectionReason(val label: String) {
    /** QueueBox did not find the idempotency key path in the payload. */
    EXTRACTION_FAILED("extraction_failed"),

    /** The inbox transform rejected the payload. */
    TRANSFORM_FAILED("transform_failed"),

    /** The repository did not store the message. */
    STORAGE_FAILED("storage_failed")
}

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

    // F-052: the metric gaps. Every method has an empty default body, so an existing
    // implementation keeps compiling.

    /**
     * Record one message that reached the destination.
     */
    fun recordDestinationSuccess(destination: String) {}

    /**
     * Record one message that the destination did not accept.
     */
    fun recordDestinationFailure(destination: String) {}

    /**
     * Update the number of messages that wait for a publish to one destination.
     */
    fun changeQueueDepth(destination: String, delta: Long) {}

    /**
     * Record one transform failure under the configured error strategy.
     */
    fun recordTransformFailure(strategy: String) {}

    /**
     * Record one inbox message that QueueBox did not accept.
     */
    fun recordInboxRejection(reason: InboxRejectionReason) {}

    /**
     * Record one HTTP publish response. The metric carries the status class only.
     */
    fun recordHttpStatus(statusCode: Int) {}
}
