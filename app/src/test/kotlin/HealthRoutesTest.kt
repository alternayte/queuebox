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
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the shipped health route wiring.
 */
class HealthRoutesTest {

    private fun ApplicationTestBuilder.setup(healthManager: HealthManager) {
        application { configureHealthApplication(healthManager) }
    }

    private fun Application.configureHealthApplication(healthManager: HealthManager) {
        install(ContentNegotiation) { json() }
        configureHealthRoutes(healthManager)
    }

    @Test
    fun `returns 200 when the health manager reports healthy`() = testApplication {
        val healthManager = mockk<HealthManager>()
        io.mockk.every { healthManager.check() } returns HealthStatus(
            status = "healthy",
            components = mapOf("database" to ComponentHealth(status = "up"))
        )
        setup(healthManager)

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("healthy"))
    }

    @Test
    fun `returns 503 when the health manager reports unhealthy`() = testApplication {
        val healthManager = mockk<HealthManager>()
        io.mockk.every { healthManager.check() } returns HealthStatus(
            status = "unhealthy",
            components = mapOf("database" to ComponentHealth(status = "down"))
        )
        setup(healthManager)

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("unhealthy"))
    }
}
