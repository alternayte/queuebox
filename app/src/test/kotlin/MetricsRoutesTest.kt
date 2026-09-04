package org.nxtspec.app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.nxtspec.metrics.InboxRejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-052: every metric that the README documents must appear in the scrape output.
 *
 * The test drives one record call per metric family and then asserts the name.
 */
class MetricsRoutesTest {

    private val documentedMetrics = listOf(
        "queuebox_outbox_messages_total",
        "queuebox_outbox_messages_pending",
        "queuebox_outbox_processing_duration_seconds",
        "queuebox_outbox_publish_duration_seconds",
        "queuebox_outbox_messages_reclaimed_total",
        "queuebox_outbox_process_errors_total",
        "queuebox_outbox_destination_messages_total",
        "queuebox_outbox_queue_depth",
        "queuebox_transform_failures_total",
        "queuebox_http_publish_responses_total",
        "queuebox_inbox_messages_total",
        "queuebox_inbox_relay_errors_total",
        "queuebox_inbox_rejections_total",
        "queuebox_cleanup_messages_deleted_total",
        "queuebox_cleanup_duration_seconds",
        "queuebox_cleanup_last_run_timestamp",
        "queuebox_uptime_seconds"
    )

    // The Prometheus exporter removes the `_info` suffix, so `queuebox_info` appears as
    // `queuebox`.
    private val infoMetricLine = "# TYPE queuebox gauge"

    private fun exerciseEveryMetric(collector: MetricsCollector) {
        collector.recordMessageSent()
        collector.recordMessageFailed()
        collector.recordMessageDead()
        collector.recordMessageReclaimed(1)
        collector.recordProcessError()
        collector.recordProcessingDuration(5)
        collector.recordPublishDuration(5, "http")
        collector.updatePendingCount(1)
        collector.recordDestinationSuccess("orders-api")
        collector.recordDestinationFailure("orders-api")
        collector.changeQueueDepth("orders-api", 2)
        collector.recordTransformFailure("fail")
        collector.recordHttpStatus(503)
        collector.recordInboxReceived()
        collector.recordInboxDuplicate()
        collector.recordInboxForwarded()
        collector.recordInboxRelayError()
        collector.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED)
        collector.recordCleanupRun("outbox", 1, 1_000_000)
    }

    @Test
    fun `scrape carries every documented metric name`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)
        exerciseEveryMetric(collector)

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        val missing = documentedMetrics.filterNot { body.contains(it) }
        assertTrue(missing.isEmpty(), "The scrape output misses: $missing")
        assertTrue(body.contains(infoMetricLine), "The scrape output misses the info metric.")
    }

    @Test
    fun `scrape carries the bounded labels of the new counters`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)
        exerciseEveryMetric(collector)

        application {
            configureMetricsRoutes(registry)
        }

        val body = client.get("/metrics").bodyAsText()

        assertTrue(body.contains("status_class=\"5xx\""))
        assertTrue(body.contains("strategy=\"fail\""))
        assertTrue(body.contains("reason=\"extraction_failed\""))
        assertTrue(body.contains("destination=\"orders-api\""))
    }

    @Test
    fun `the readme documents every metric that the scrape carries`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)
        exerciseEveryMetric(collector)

        application {
            configureMetricsRoutes(registry)
        }

        val body = client.get("/metrics").bodyAsText()
        val readme = readmeText()

        val emitted = body.lineSequence()
            .filter { it.startsWith("# TYPE ") }
            .map { it.split(" ")[2] }
            .map { it.removeSuffix("_created").removeSuffix("_max").removeSuffix("_total") }
            .filter { it.startsWith("queuebox_") }
            .toSortedSet()

        val undocumented = emitted.filterNot { readme.contains(it) }
        assertTrue(undocumented.isEmpty(), "The README misses: $undocumented")
    }

    private fun readmeText(): String {
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidate = java.io.File(dir, "README.md")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: error("No README.md above the working directory.")
        }
    }
}
