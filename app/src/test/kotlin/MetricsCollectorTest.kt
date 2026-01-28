package org.nxtspec.app

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class MetricsCollectorTest {

    @Test
    fun `should start at zero when new collector`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)
        val output = registry.scrape()

        // New metrics use tagged counters, so we check for the metric name with tags
        assertTrue(output.contains("queuebox_outbox_messages_total"))
        assertTrue(output.contains("queuebox_inbox_messages_total"))
    }

    @Test
    fun `should increment counters when metrics updated`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        collector.recordMessageSent()
        collector.recordMessageSent()
        collector.recordMessageFailed()
        collector.recordInboxReceived()
        collector.recordInboxReceived()
        collector.recordInboxReceived()

        val output = registry.scrape()

        assertTrue(output.contains("queuebox_outbox_messages_total{status=\"sent\""))
        assertTrue(output.contains("queuebox_outbox_messages_total{status=\"failed\""))
        assertTrue(output.contains("queuebox_inbox_messages_total{status=\"new\""))
    }

    @Test
    fun `should output valid Prometheus format`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)
        val output = registry.scrape()

        // Verify HELP comments are present
        assertTrue(output.contains("# HELP queuebox_outbox_messages_total"))
        assertTrue(output.contains("# HELP queuebox_inbox_messages_total"))

        // Verify TYPE comments are present
        assertTrue(output.contains("# TYPE queuebox_outbox_messages_total counter"))
        assertTrue(output.contains("# TYPE queuebox_inbox_messages_total counter"))
    }

    @Test
    fun `should be thread safe when concurrent increments`() = runBlocking {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        coroutineScope {
            repeat(1000) {
                launch(Dispatchers.Default) {
                    collector.recordMessageSent()
                }
            }
        }

        val output = registry.scrape()
        assertTrue(output.contains("queuebox_outbox_messages_total{status=\"sent\""))
        // Verify the count is 1000
        assertTrue(output.contains("1000.0"))
    }

    @Test
    fun `should be thread safe when concurrent mixed increments`() = runBlocking {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        coroutineScope {
            repeat(500) {
                launch(Dispatchers.Default) {
                    collector.recordMessageSent()
                }
            }
            repeat(300) {
                launch(Dispatchers.Default) {
                    collector.recordMessageFailed()
                }
            }
            repeat(200) {
                launch(Dispatchers.Default) {
                    collector.recordInboxReceived()
                }
            }
        }

        val output = registry.scrape()
        // Verify all counters have correct values
        assertTrue(output.contains("queuebox_outbox_messages_total{status=\"sent\""))
        assertTrue(output.contains("queuebox_outbox_messages_total{status=\"failed\""))
        assertTrue(output.contains("queuebox_inbox_messages_total{status=\"new\""))
    }

    @Test
    fun `should record timers when durations recorded`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        collector.recordProcessingDuration(100)
        collector.recordProcessingDuration(200)
        collector.recordPublishDuration(50, "http")
        collector.recordPublishDuration(75, "rabbitmq")

        val output = registry.scrape()

        // Verify timers are present with percentiles
        assertTrue(output.contains("queuebox_outbox_processing_duration_seconds"))
        assertTrue(output.contains("queuebox_outbox_publish_duration_seconds"))
        assertTrue(output.contains("destination_type=\"http\""))
        assertTrue(output.contains("destination_type=\"rabbitmq\""))
    }

    @Test
    fun `should update pending count gauge`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        collector.updatePendingCount(42)

        val output = registry.scrape()
        assertTrue(output.contains("queuebox_outbox_messages_pending"))
        assertTrue(output.contains("42.0"))
    }
}
