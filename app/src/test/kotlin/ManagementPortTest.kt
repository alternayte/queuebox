package org.nxtspec.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.nxtspec.AdminConfig
import org.nxtspec.transform.TransformEngine
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-051. A set management port moves the operational endpoints off the data port.
 */
class ManagementPortTest {

    private fun healthManager(): HealthManager {
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } throws SQLException("Connection refused")
        return HealthManager(dataSource)
    }

    private fun Application.dataPort(managementPort: Int?) {
        install(ContentNegotiation) { json() }
        configureDataPortOperationalRoutes(
            managementPort = managementPort,
            prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            healthManager = healthManager(),
            adminConfig = AdminConfig(),
            transformEngine = TransformEngine()
        )
    }

    @Test
    fun `metrics returns 404 on the data port when a management port is set`() = testApplication {
        application { dataPort(managementPort = 9090) }

        assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
    }

    @Test
    fun `health returns 404 on the data port when a management port is set`() = testApplication {
        application { dataPort(managementPort = 9090) }

        assertEquals(HttpStatusCode.NotFound, client.get("/health/live").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/health/ready").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/health").status)
    }

    @Test
    fun `metrics stays on the data port when no management port is set`() = testApplication {
        application { dataPort(managementPort = null) }

        assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `the management plane serves the operational endpoints`() = testApplication {
        // The application installs exactly the plugins that main installs on the management
        // server. A test that installs its own plugins hides a missing one.
        application {
            configureJson()
            configureOperationalRoutes(
                prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
                healthManager = healthManager(),
                adminConfig = AdminConfig(),
                transformEngine = TransformEngine()
            )
        }

        assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
    }

    @Test
    fun `the management plane answers with a json body, not a 500`() = testApplication {
        // F-051: the management server is its own Ktor application. Without content negotiation
        // every health answer is a 500, and both Kubernetes probes fail.
        application {
            configureJson()
            configureOperationalRoutes(
                prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
                healthManager = healthManager(),
                adminConfig = AdminConfig(),
                transformEngine = TransformEngine()
            )
        }

        val live = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, live.status)
        assertTrue(
            live.bodyAsText().contains("\"status\""),
            "The body must be the serialized health status. Saw: ${live.bodyAsText()}"
        )
    }
}
