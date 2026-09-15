package org.nxtspec

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class InboxRoutesHeadersTest {

    @Test
    fun `a webhook stores its request headers without the credential headers`() = testApplication {
        val mockRepository = mockk<InboxRepository>(relaxed = true)
        val stored = mutableListOf<InboxMessage>()
        coEvery { mockRepository.store(capture(stored)) } returns InboxResult.Stored
        val sources = mapOf(
            "stripe" to SourceConfig.Http(
                path = "/stripe",
                idempotencyKeyPath = "$.id",
                auth = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret("k"))
            )
        )
        application {
            this.install(ContentNegotiation) { json() }
            configureInboxRoutes(
                InboxConfig(basePath = "/inbox"),
                sources,
                InboxHandler(mockRepository, IdempotencyExtractor())
            )
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "k")
            header("x-tenant", "acme")
            header(HttpHeaders.Cookie, "session=1")
            setBody("""{"id":"evt_1"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val headers = stored.single().headers.mapKeys { it.key.lowercase() }
        assertEquals("acme", headers["x-tenant"])
        assertEquals(null, headers["x-api-key"])
        assertEquals(null, headers["cookie"])
    }

    @Test
    fun `a filtered webhook answers 202 and stores nothing`() = testApplication {
        val mockRepository = mockk<InboxRepository>(relaxed = true)
        val sources = mapOf(
            "stripe" to SourceConfig.Http(
                path = "/stripe",
                idempotencyKeyPath = "$.id",
                filter = HeaderFilterConfig(require = listOf(HeaderRule("x-tenant", equals = "acme")))
            )
        )
        application {
            this.install(ContentNegotiation) { json() }
            configureInboxRoutes(
                InboxConfig(basePath = "/inbox"),
                sources,
                InboxHandler(mockRepository, IdempotencyExtractor())
            )
        }

        val response = client.post("/inbox/stripe") {
            contentType(ContentType.Application.Json)
            header("x-tenant", "other")
            setBody("""{"id":"evt_1"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        coVerify(exactly = 0) { mockRepository.store(any()) }
    }
}
