package org.nxtspec.app

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckTest {

    private fun toJson(health: HealthStatus): String {
        val components = health.components.entries.joinToString(",") { (key, value) ->
            "\"$key\":{\"status\":\"${value.status}\"}"
        }
        return "{\"status\":\"${health.status}\",\"components\":{$components}}"
    }

    @Test
    fun `should return 200 when database is healthy`() = testApplication {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)

        routing {
            get("/health") {
                val health = healthManager.check()
                val status = if (health.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respondText(toJson(health), ContentType.Application.Json, status)
            }
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"healthy\""))
        assertTrue(body.contains("\"database\""))
    }

    @Test
    fun `should return 503 when database is unhealthy`() = testApplication {
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } throws SQLException("Connection refused")

        val healthManager = HealthManager(mockDataSource)

        routing {
            get("/health") {
                val health = healthManager.check()
                val status = if (health.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respondText(toJson(health), ContentType.Application.Json, status)
            }
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"unhealthy\""))
    }

    @Test
    fun `should return JSON content type`() = testApplication {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)

        routing {
            get("/health") {
                val health = healthManager.check()
                val status = if (health.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respondText(toJson(health), ContentType.Application.Json, status)
            }
        }

        val response = client.get("/health")

        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }
}
