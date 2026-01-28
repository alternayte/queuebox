package org.nxtspec.app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {

    @Test
    fun `should return Prometheus format when metrics requested`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())

        val body = response.bodyAsText()
        assertTrue(body.contains("# HELP queuebox_outbox_messages_total"))
        assertTrue(body.contains("# TYPE queuebox_outbox_messages_total counter"))
    }

    @Test
    fun `should reflect updated counters when metrics incremented`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        collector.recordMessageSent()
        collector.recordMessageSent()
        collector.recordMessageFailed()
        collector.recordInboxReceived()

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("queuebox_outbox_messages_total{status=\"sent\""))
        assertTrue(body.contains("queuebox_outbox_messages_total{status=\"failed\""))
        assertTrue(body.contains("queuebox_inbox_messages_total{status=\"new\""))
    }

    @Test
    fun `should return 200 when metrics endpoint called`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `should include all metric types in response`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")
        val body = response.bodyAsText()

        // Verify key metrics are present
        assertTrue(body.contains("queuebox_outbox_messages_total"))
        assertTrue(body.contains("queuebox_inbox_messages_total"))
        assertTrue(body.contains("queuebox_uptime_seconds"))
    }

    @Test
    fun `should include timer metrics with percentiles`() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val collector = MetricsCollector(registry)

        collector.recordProcessingDuration(100)
        collector.recordPublishDuration(50, "http")

        application {
            configureMetricsRoutes(registry)
        }

        val response = client.get("/metrics")
        val body = response.bodyAsText()

        // Verify timer metrics and percentiles are present
        assertTrue(body.contains("queuebox_outbox_processing_duration_seconds"))
        assertTrue(body.contains("queuebox_outbox_publish_duration_seconds"))
        assertTrue(body.contains("quantile=\"0.5\""))
        assertTrue(body.contains("quantile=\"0.95\""))
        assertTrue(body.contains("quantile=\"0.99\""))
    }
}
