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
}
