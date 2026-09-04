package org.nxtspec.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.nxtspec.BuildInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Central metrics definitions for QueueBox using Micrometer.
 * All metric names follow Prometheus naming conventions with `queuebox_` prefix.
 */
class QueueBoxMetrics(private val registry: MeterRegistry) {

    // Uptime tracking
    private val startTime = System.currentTimeMillis()
    private val pendingMessageCount = AtomicLong(0)

    // Cleanup tracking (last run timestamps per table)
    private val cleanupLastRunTimestamps = mutableMapOf<String, AtomicLong>()

    // Outbox counters
    val outboxMessagesSent: Counter = Counter.builder("queuebox_outbox_messages_total")
        .description("Total outbox messages by status")
        .tag("status", "sent")
        .register(registry)

    val outboxMessagesFailed: Counter = Counter.builder("queuebox_outbox_messages_total")
        .description("Total outbox messages by status")
        .tag("status", "failed")
        .register(registry)

    val outboxMessagesDead: Counter = Counter.builder("queuebox_outbox_messages_total")
        .description("Total outbox messages by status")
        .tag("status", "dead")
        .register(registry)

    // Outbox pending gauge
    val outboxMessagesPending: Gauge = Gauge.builder("queuebox_outbox_messages_pending", pendingMessageCount) { it.get().toDouble() }
        .description("Current number of pending outbox messages")
        .register(registry)

    // Outbox timers
    val outboxProcessingDuration: Timer = Timer.builder("queuebox_outbox_processing_duration_seconds")
        .description("Time taken to process outbox messages")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)

    // Publish duration timers (one per destination type)
    private val publishTimers = mutableMapOf<String, Timer>()

    // Cleanup counters (one per table)
    private val cleanupCounters = mutableMapOf<String, Counter>()

    // Cleanup timers (one per table)
    private val cleanupTimers = mutableMapOf<String, Timer>()

    // F-052: one counter per destination and outcome. A destination name comes from the
    // configuration, so the label set stays bounded.
    private val destinationCounters = mutableMapOf<Pair<String, String>, Counter>()

    // F-052: one gauge per destination. The value is the number of messages that wait for a
    // publish to that destination.
    private val queueDepths = mutableMapOf<String, AtomicLong>()

    // F-052: one counter per transform error strategy.
    private val transformFailureCounters = mutableMapOf<String, Counter>()

    // F-052: one counter per inbox rejection reason. The reason is a fixed enumeration.
    private val inboxRejectionCounters = mutableMapOf<String, Counter>()

    // F-052: one counter per HTTP status class. A raw status code is never a label.
    private val httpStatusCounters = mutableMapOf<String, Counter>()

    fun getPublishTimer(destinationType: String): Timer {
        return publishTimers.getOrPut(destinationType) {
            Timer.builder("queuebox_outbox_publish_duration_seconds")
                .description("Time taken to publish to destination")
                .tag("destination_type", destinationType)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
        }
    }

    // Inbox counters
    val inboxMessagesNew: Counter = Counter.builder("queuebox_inbox_messages_total")
        .description("Total inbox messages by status")
        .tag("status", "new")
        .register(registry)

    val outboxProcessErrors: Counter = Counter.builder("queuebox_outbox_process_errors_total")
        .description("Total errors that stopped the processing of one outbox message")
        .register(registry)

    val outboxMessagesReclaimed: Counter = Counter.builder("queuebox_outbox_messages_reclaimed_total")
        .description("Total outbox messages returned to pending after a stale claim")
        .register(registry)

    val inboxMessagesForwarded: Counter = Counter.builder("queuebox_inbox_messages_total")
        .description("Total inbox messages by status")
        .tag("status", "forwarded")
        .register(registry)

    val inboxRelayErrors: Counter = Counter.builder("queuebox_inbox_relay_errors_total")
        .description("Total inbox relay errors")
        .register(registry)

    val inboxMessagesDuplicate: Counter = Counter.builder("queuebox_inbox_messages_total")
        .description("Total inbox messages by status")
        .tag("status", "duplicate")
        .register(registry)

    // Uptime gauge
    val uptime: Gauge = Gauge.builder("queuebox_uptime_seconds", this) {
        (System.currentTimeMillis() - it.startTime) / 1000.0
    }
        .description("Application uptime in seconds")
        .register(registry)

    // Application info gauge
    val info: Gauge = Gauge.builder("queuebox_info", this) { 1.0 }
        .description("QueueBox application info")
        // F-053: the version comes from the build, not from a literal.
        .tag("version", BuildInfo.version)
        .register(registry)

    /**
     * Update the pending message count for the gauge.
     */
    fun setPendingMessageCount(count: Long) {
        pendingMessageCount.set(count)
    }

    /**
     * Record processing duration in milliseconds.
     */
    fun recordProcessingDuration(durationMs: Long) {
        outboxProcessingDuration.record(durationMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Record publish duration in milliseconds for a specific destination type.
     */
    fun recordPublishDuration(durationMs: Long, destinationType: String) {
        getPublishTimer(destinationType).record(durationMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Get or create a cleanup deleted counter for a specific table.
     */
    private fun getCleanupCounter(table: String): Counter {
        return cleanupCounters.getOrPut(table) {
            Counter.builder("queuebox_cleanup_messages_deleted_total")
                .description("Total messages deleted by retention cleanup")
                .tag("table", table)
                .register(registry)
        }
    }

    /**
     * Get or create a cleanup duration timer for a specific table.
     */
    private fun getCleanupTimer(table: String): Timer {
        return cleanupTimers.getOrPut(table) {
            Timer.builder("queuebox_cleanup_duration_seconds")
                .description("Time taken to run retention cleanup")
                .tag("table", table)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
        }
    }

    /**
     * Get or create a cleanup last run timestamp gauge for a specific table.
     */
    private fun getOrCreateCleanupLastRunGauge(table: String): AtomicLong {
        return cleanupLastRunTimestamps.getOrPut(table) {
            val timestamp = AtomicLong(0)
            Gauge.builder("queuebox_cleanup_last_run_timestamp", timestamp) { it.get().toDouble() }
                .description("Unix timestamp of last cleanup run")
                .tag("table", table)
                .register(registry)
            timestamp
        }
    }

    /**
     * Record a cleanup run with deleted count and duration.
     */
    fun recordCleanupRun(table: String, deleted: Int, durationNanos: Long) {
        getCleanupCounter(table).increment(deleted.toDouble())
        getCleanupTimer(table).record(durationNanos, TimeUnit.NANOSECONDS)
        getOrCreateCleanupLastRunGauge(table).set(System.currentTimeMillis() / 1000)
    }

    // --- F-052: the metric gaps ---

    @Synchronized
    private fun getDestinationCounter(destination: String, outcome: String): Counter {
        return destinationCounters.getOrPut(destination to outcome) {
            Counter.builder("queuebox_outbox_destination_messages_total")
                .description("Total outbox messages per destination and outcome")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .register(registry)
        }
    }

    /**
     * Record one message that reached the destination.
     */
    fun recordDestinationSuccess(destination: String) {
        getDestinationCounter(destination, OUTCOME_SUCCESS).increment()
    }

    /**
     * Record one message that the destination did not accept.
     */
    fun recordDestinationFailure(destination: String) {
        getDestinationCounter(destination, OUTCOME_FAILURE).increment()
    }

    /**
     * Set the number of messages that wait for a publish to one destination.
     */
    @Synchronized
    fun setQueueDepth(destination: String, depth: Long) {
        val holder = queueDepths.getOrPut(destination) {
            val value = AtomicLong(0)
            Gauge.builder("queuebox_outbox_queue_depth", value) { it.get().toDouble() }
                .description("Messages that wait for a publish to the destination")
                .tag("destination", destination)
                .register(registry)
            value
        }
        holder.set(depth)
    }

    /**
     * Record one transform failure under the error strategy that the configuration selects.
     */
    @Synchronized
    fun recordTransformFailure(strategy: String) {
        transformFailureCounters.getOrPut(strategy) {
            Counter.builder("queuebox_transform_failures_total")
                .description("Total transform failures by error strategy")
                .tag("strategy", strategy)
                .register(registry)
        }.increment()
    }

    /**
     * Record one inbox message that QueueBox did not accept.
     */
    @Synchronized
    fun recordInboxRejection(reason: InboxRejectionReason) {
        inboxRejectionCounters.getOrPut(reason.label) {
            Counter.builder("queuebox_inbox_rejections_total")
                .description("Total inbox messages that QueueBox rejected, by reason")
                .tag("reason", reason.label)
                .register(registry)
        }.increment()
    }

    /**
     * Record one HTTP response under its status class.
     *
     * The label is the status class, for example "5xx". A raw status code would make the label
     * set unbounded.
     */
    @Synchronized
    fun recordHttpStatus(statusCode: Int) {
        val statusClass = statusClassOf(statusCode)
        httpStatusCounters.getOrPut(statusClass) {
            Counter.builder("queuebox_http_publish_responses_total")
                .description("Total HTTP publish responses by status class")
                .tag("status_class", statusClass)
                .register(registry)
        }.increment()
    }

    companion object {
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_FAILURE = "failure"

        /**
         * Maps a status code to its class. An unexpected code maps to "other".
         */
        fun statusClassOf(statusCode: Int): String = when (statusCode / 100) {
            1 -> "1xx"
            2 -> "2xx"
            3 -> "3xx"
            4 -> "4xx"
            5 -> "5xx"
            else -> "other"
        }
    }
}
