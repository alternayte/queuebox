package org.nxtspec.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
        .tag("version", "0.1.0")
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
}
