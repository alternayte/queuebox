package org.nxtspec.e2e

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.nxtspec.AdminConfig
import org.nxtspec.Destination
import org.nxtspec.InboxAuthConfig
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxRepository
import org.nxtspec.RouteConfig
import org.nxtspec.Secret
import org.nxtspec.app.configureAdminRoutes
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.repository.ReplayResponse
import org.nxtspec.transform.TransformEngine
import kotlin.test.assertEquals

/**
 * Covers F-096: POST /admin/replay moves selected outbox rows back to state 'pending'.
 *
 * Runs against the real PostgreSQL outbox repository provided by [E2ETestBase], so the state
 * filter, the time-range filter, and the destination-to-topic resolution all run against a real
 * table rather than a fake.
 */
class AdminReplayRouteTest : E2ETestBase() {

    private val repository = OutboxRepository()

    private val bearerAdmin = AdminConfig(
        enabled = true,
        auth = InboxAuthConfig.Bearer(token = Secret(TOKEN))
    )

    // Two routes, so a destination filter must resolve through the router's first-match rule
    // rather than a plain topic prefix match. `orders.paid` matches both patterns; the first
    // route listed wins, exactly as the relay itself would route it.
    private val router = MessageRouter(
        routes = listOf(
            RouteConfig(topicPattern = "orders.*", destination = "orders-service"),
            RouteConfig(topicPattern = "*.paid", destination = "billing-service")
        ),
        destinations = mapOf(
            "orders-service" to Destination.Http(name = "orders-service", baseUrl = "http://orders"),
            "billing-service" to Destination.Http(name = "billing-service", baseUrl = "http://billing")
        )
    )

    private fun ApplicationTestBuilder.setupApp() {
        application {
            install(ContentNegotiation) { json() }
            configureAdminRoutes(bearerAdmin, InboxAuthValidator(), TransformEngine(), repository, router)
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
    fun `an authenticated replay with no filter still answers 400, not 401 or 404`() = testApplication {
        // The 401 test alone would also pass if the route did not exist at all, or if a 404
        // happened to fall through unauthenticated: "not 401" is true for both a guarded route
        // and an absent one. Only a present, guarded, reachable route answers 400 to an
        // authenticated request with an empty filter, so that is the assertion here.
        setupApp()

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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

    @Test
    fun `a replay by destination resolves through the router's first-match rule`() = testApplication {
        setupApp()

        // "orders.paid" matches both route patterns. The router's first-match rule assigns it
        // to "orders-service", not "billing-service", so a replay of "billing-service" must
        // leave it alone.
        val ordersPaid = insertOutboxMessage(topic = "orders.paid", state = "sent")
        val billingCreated = insertOutboxMessage(topic = "billing.created", state = "sent")

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"destination":"billing-service"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
        assertEquals("sent", getOutboxMessageState(ordersPaid))
        assertEquals("sent", getOutboxMessageState(billingCreated))
    }

    @Test
    fun `a replay by destination moves the topics that resolve to it`() = testApplication {
        setupApp()

        val ordersCreated = insertOutboxMessage(topic = "orders.created", state = "sent")
        // "orders.paid" resolves to "orders-service" too, by the router's first-match rule,
        // even though its pattern also matches the billing route.
        val ordersPaid = insertOutboxMessage(topic = "orders.paid", state = "dead")
        val billingCreated = insertOutboxMessage(topic = "billing.created", state = "sent")

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"destination":"orders-service"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
        assertEquals("pending", getOutboxMessageState(ordersCreated))
        assertEquals("pending", getOutboxMessageState(ordersPaid))
        assertEquals("sent", getOutboxMessageState(billingCreated))
    }

    @Test
    fun `a replay with both destination and topics moves only the intersection`() = testApplication {
        setupApp()

        // "orders-service" resolves to both "orders.created" and "orders.paid". The caller
        // narrows further to "orders.created" alone, so "orders.paid" must not move even
        // though it resolves to the same destination. See F-096.
        val ordersCreated = insertOutboxMessage(topic = "orders.created", state = "sent")
        val ordersPaid = insertOutboxMessage(topic = "orders.paid", state = "sent")
        val billingCreated = insertOutboxMessage(topic = "billing.created", state = "sent")

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"destination":"orders-service","topics":["orders.created"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
        assertEquals("pending", getOutboxMessageState(ordersCreated))
        assertEquals("sent", getOutboxMessageState(ordersPaid))
        assertEquals("sent", getOutboxMessageState(billingCreated))
    }

    @Test
    fun `a replay with destination and a disjoint topics list moves zero rows`() = testApplication {
        setupApp()

        // The caller's `topics` names a topic that does not resolve to this destination, so the
        // intersection is empty. The replay must move zero rows, never fall back to the
        // destination's full topic set.
        val ordersCreated = insertOutboxMessage(topic = "orders.created", state = "sent")

        val response = client.post("/admin/replay") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"destination":"orders-service","topics":["billing.created"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
        assertEquals("sent", getOutboxMessageState(ordersCreated))
    }
}

private const val TOKEN = "admin-replay-token"
