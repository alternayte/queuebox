package org.nxtspec.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Item 10 of section 11 of `hardening-doc.md`. Every status code that a document states for an
 * endpoint must come out of a test.
 *
 * Two documented answers had no test. `docs/getting-started.md` states that `GET /` returns
 * "QueueBox is running!". The README quick start and `docs/getting-started.md` state that a ready
 * instance answers `GET /health` with 200. The only alias test asserts the 503 answer, so the 200
 * answer of the alias was untested.
 */
class RootAndHealthRoutesTest {

    private fun ApplicationTestBuilder.setupRoot() {
        application { configureRootApplication() }
    }

    private fun Application.configureRootApplication() {
        install(ContentNegotiation) { json() }
        configureRouting()
    }

    private fun ApplicationTestBuilder.setupHealth(healthManager: HealthManager) {
        application { configureHealthApplication(healthManager) }
    }

    private fun Application.configureHealthApplication(healthManager: HealthManager) {
        install(ContentNegotiation) { json() }
        configureHealthRoutes(healthManager)
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
    fun `the root route returns 200 with the documented text`() = testApplication {
        setupRoot()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("QueueBox is running!", response.bodyAsText())
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
    }

    @Test
    fun `health returns 200 when every component is up`() = testApplication {
        setupHealth(HealthManager(workingDataSource()))

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"healthy\""), "the body was $body")
    }

    @Test
    fun `health and readiness answer the same status and body when every component is up`() = testApplication {
        setupHealth(HealthManager(workingDataSource()))

        val alias = client.get("/health")
        val readiness = client.get("/health/ready")

        assertEquals(readiness.status, alias.status)
        assertEquals(readiness.bodyAsText(), alias.bodyAsText())
    }
}
