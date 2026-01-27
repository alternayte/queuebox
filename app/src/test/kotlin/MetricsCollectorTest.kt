package org.nxtspec.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class MetricsCollectorTest {

    @Test
    fun `should start at zero when new collector`() {
        val collector = MetricsCollector()
        val output = collector.toPrometheusFormat()

        assertTrue(output.contains("queuebox_messages_processed_total 0"))
        assertTrue(output.contains("queuebox_messages_failed_total 0"))
        assertTrue(output.contains("queuebox_inbox_received_total 0"))
    }

    @Test
    fun `should increment counters when metrics updated`() {
        val collector = MetricsCollector()

        collector.incrementProcessed()
        collector.incrementProcessed()
        collector.incrementFailed()
        collector.incrementInboxReceived()
        collector.incrementInboxReceived()
        collector.incrementInboxReceived()

        val output = collector.toPrometheusFormat()

        assertTrue(output.contains("queuebox_messages_processed_total 2"))
        assertTrue(output.contains("queuebox_messages_failed_total 1"))
        assertTrue(output.contains("queuebox_inbox_received_total 3"))
    }

    @Test
    fun `should output valid Prometheus format`() {
        val collector = MetricsCollector()
        val output = collector.toPrometheusFormat()

        // Verify HELP comments
        assertTrue(output.contains("# HELP queuebox_messages_processed_total"))
        assertTrue(output.contains("# HELP queuebox_messages_failed_total"))
        assertTrue(output.contains("# HELP queuebox_inbox_received_total"))

        // Verify TYPE comments
        assertTrue(output.contains("# TYPE queuebox_messages_processed_total counter"))
        assertTrue(output.contains("# TYPE queuebox_messages_failed_total counter"))
        assertTrue(output.contains("# TYPE queuebox_inbox_received_total counter"))
    }

    @Test
    fun `should be thread safe when concurrent increments`() = runBlocking {
        val collector = MetricsCollector()

        coroutineScope {
            repeat(1000) {
                launch(Dispatchers.Default) {
                    collector.incrementProcessed()
                }
            }
        }

        val output = collector.toPrometheusFormat()
        assertTrue(output.contains("queuebox_messages_processed_total 1000"))
    }

    @Test
    fun `should be thread safe when concurrent mixed increments`() = runBlocking {
        val collector = MetricsCollector()

        coroutineScope {
            repeat(500) {
                launch(Dispatchers.Default) {
                    collector.incrementProcessed()
                }
            }
            repeat(300) {
                launch(Dispatchers.Default) {
                    collector.incrementFailed()
                }
            }
            repeat(200) {
                launch(Dispatchers.Default) {
                    collector.incrementInboxReceived()
                }
            }
        }

        val output = collector.toPrometheusFormat()
        assertTrue(output.contains("queuebox_messages_processed_total 500"))
        assertTrue(output.contains("queuebox_messages_failed_total 300"))
        assertTrue(output.contains("queuebox_inbox_received_total 200"))
    }
}
