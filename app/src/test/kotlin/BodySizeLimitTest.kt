package org.nxtspec.app

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers F-023. The cap applies to every route, not only to the inbox route.
 */
class BodySizeLimitTest {

    private fun Application.setup(maxBytes: Long) {
        configureBodySizeLimit(maxBytes)
        routing {
            post("/anything") { call.respondText("ok") }
        }
    }

    @Test
    fun `rejects a body over the limit on any route`() = testApplication {
        application { setup(100) }

        val response = client.post("/anything") { setBody("x".repeat(101)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `accepts a body at the limit`() = testApplication {
        application { setup(100) }

        val response = client.post("/anything") { setBody("x".repeat(100)) }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
