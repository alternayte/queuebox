package org.nxtspec.app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {

    @Test
    fun `should return Prometheus format when metrics requested`() = testApplication {
        val collector = MetricsCollector()

        application {
            configureMetricsRoutes(collector)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())

        val body = response.bodyAsText()
        assertTrue(body.contains("# HELP queuebox_messages_processed_total"))
        assertTrue(body.contains("# TYPE queuebox_messages_processed_total counter"))
        assertTrue(body.contains("queuebox_messages_processed_total 0"))
    }

    @Test
    fun `should reflect updated counters when metrics incremented`() = testApplication {
        val collector = MetricsCollector()

        collector.incrementProcessed()
        collector.incrementProcessed()
        collector.incrementFailed()
        collector.incrementInboxReceived()

        application {
            configureMetricsRoutes(collector)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("queuebox_messages_processed_total 2"))
        assertTrue(body.contains("queuebox_messages_failed_total 1"))
        assertTrue(body.contains("queuebox_inbox_received_total 1"))
    }

    @Test
    fun `should return 200 when metrics endpoint called`() = testApplication {
        val collector = MetricsCollector()

        application {
            configureMetricsRoutes(collector)
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `should include all metric types in response`() = testApplication {
        val collector = MetricsCollector()

        application {
            configureMetricsRoutes(collector)
        }

        val response = client.get("/metrics")
        val body = response.bodyAsText()

        // Verify all three metrics are present
        assertTrue(body.contains("queuebox_messages_processed_total"))
        assertTrue(body.contains("queuebox_messages_failed_total"))
        assertTrue(body.contains("queuebox_inbox_received_total"))
    }
}
