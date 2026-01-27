package org.nxtspec.app

import java.util.concurrent.atomic.AtomicLong

class MetricsCollector {
    private val messagesProcessed = AtomicLong(0)
    private val messagesFailed = AtomicLong(0)
    private val inboxReceived = AtomicLong(0)

    fun incrementProcessed() {
        messagesProcessed.incrementAndGet()
    }

    fun incrementFailed() {
        messagesFailed.incrementAndGet()
    }

    fun incrementInboxReceived() {
        inboxReceived.incrementAndGet()
    }

    fun toPrometheusFormat(): String = buildString {
        appendLine("# HELP queuebox_messages_processed_total Total messages processed")
        appendLine("# TYPE queuebox_messages_processed_total counter")
        appendLine("queuebox_messages_processed_total ${messagesProcessed.get()}")
        appendLine()
        appendLine("# HELP queuebox_messages_failed_total Total messages failed")
        appendLine("# TYPE queuebox_messages_failed_total counter")
        appendLine("queuebox_messages_failed_total ${messagesFailed.get()}")
        appendLine()
        appendLine("# HELP queuebox_inbox_received_total Total inbox messages received")
        appendLine("# TYPE queuebox_inbox_received_total counter")
        appendLine("queuebox_inbox_received_total ${inboxReceived.get()}")
    }
}
