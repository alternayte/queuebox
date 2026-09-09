package org.nxtspec.e2e

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.nxtspec.AdminConfig
import org.nxtspec.InboxAuthConfig
import org.nxtspec.OutboxRepository
import org.nxtspec.Secret
import org.nxtspec.app.configureAdminRoutes
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.repository.ReplayResponse
import org.nxtspec.transform.TransformEngine
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Covers F-096: POST /admin/replay moves selected outbox rows back to state 'pending'.
 *
 * Runs against the real PostgreSQL outbox repository provided by [E2ETestBase], so the state
 * filter and the time-range filter run against a real table rather than a fake.
 */
class AdminReplayRouteTest : E2ETestBase() {

    private val repository = OutboxRepository()

    private val bearerAdmin = AdminConfig(
        enabled = true,
        auth = InboxAuthConfig.Bearer(token = Secret(TOKEN))
    )

    private fun ApplicationTestBuilder.setupApp() {
        application {
            install(ContentNegotiation) { json() }
            configureAdminRoutes(bearerAdmin, InboxAuthValidator(), TransformEngine(), repository)
        }
    }

    @Test
    fun `a replay with no filter answers 400`() = testApplication {
        setupApp()

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an unauthenticated replay answers 401`() = testApplication {
        setupApp()

        val response = client.post("/admin/replay") {
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"orders.created"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `an authenticated replay to the same path answers something other than 401`() = testApplication {
        // The 401 test alone would also pass if the route did not exist at all. This request
        // carries valid credentials, so a guarded-but-present route answers something other
        // than 401 here, distinguishing "guarded" from "absent".
        setupApp()

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertNotEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a replay answers the count that moved`() = testApplication {
        setupApp()

        val first = insertOutboxMessage(topic = "orders.created", state = "sent")
        val second = insertOutboxMessage(topic = "orders.created", state = "sent")
        val claimed = insertOutboxMessage(topic = "orders.created", state = "processing")

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"orders.created"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Two sent rows move. The claimed row is in state 'processing', so it must not.
        assertEquals(2L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
        assertEquals("processing", getOutboxMessageState(claimed))
        assertEquals("pending", getOutboxMessageState(first))
        assertEquals("pending", getOutboxMessageState(second))
    }
}

private const val TOKEN = "admin-replay-token"
