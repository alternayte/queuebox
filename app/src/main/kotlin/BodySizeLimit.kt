package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * Rejects a request whose declared body is larger than the cap, on every route. See F-023.
 *
 * The Netty engine has no total request size setting, so the cap runs here. The inbox route
 * applies a second, counting cap, because a chunked request declares no length.
 */
fun Application.configureBodySizeLimit(maxBytes: Long) {
    intercept(ApplicationCallPipeline.Setup) {
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > maxBytes) {
            // respondText needs no content negotiation, so the limit works on any route.
            call.respondText(
                text = """{"error":"Request body exceeds $maxBytes bytes"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.PayloadTooLarge
            )
            finish()
        }
    }
}
