package org.nxtspec.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the shipped health route wiring. See F-049 and F-050.
 */
class HealthRoutesTest {

    private fun ApplicationTestBuilder.setup(healthManager: HealthManager) {
        application { configureHealthApplication(healthManager) }
    }

    private fun Application.configureHealthApplication(healthManager: HealthManager) {
        install(ContentNegotiation) { json() }
        configureHealthRoutes(healthManager)
    }

    private fun brokenDataSource(): DataSource {
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } throws SQLException("Connection refused")
        return dataSource
    }

    private fun workingDataSource(): DataSource {
        val connection = mockk<java.sql.Connection>()
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.isValid(any()) } returns true
        every { connection.close() } returns Unit
        return dataSource
    }

    @Test
    fun `liveness returns 200 with a broken data source`() = testApplication {
        setup(HealthManager(brokenDataSource()))

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"healthy\""))
    }

    @Test
    fun `readiness returns 503 with a broken data source`() = testApplication {
        setup(HealthManager(brokenDataSource()))

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"unhealthy\""))
    }

    @Test
    fun `readiness returns 200 when every component is up`() = testApplication {
        setup(HealthManager(workingDataSource()))

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"healthy\""))
    }

    @Test
    fun `health stays an alias of readiness`() = testApplication {
        setup(HealthManager(brokenDataSource()))

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"unhealthy\""))
    }

    @Test
    fun `readiness names the stopped component`() = testApplication {
        var pollerRunning = true
        val healthManager = HealthManager(
            workingDataSource(),
            listOf(SimpleHealthContributor("outbox-poller") { pollerRunning })
        )
        setup(healthManager)

        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)

        pollerRunning = false
        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("outbox-poller"))
        assertTrue(body.contains("down"))
    }
}
