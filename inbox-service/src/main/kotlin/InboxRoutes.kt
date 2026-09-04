package org.nxtspec

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.nxtspec.auth.AuthResult
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.auth.RawBodyKey
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

fun Application.configureInboxRoutes(
    config: InboxConfig,
    sources: Map<String, SourceConfig>,
    handler: InboxHandler,
    authValidator: InboxAuthValidator = InboxAuthValidator()
) {
    // F-023: DoubleReceive is not installed. It buffers every request body without a cap.
    // The route now reads the body once, under the cap, and reuses those bytes for the HMAC
    // check and for the JSON parse.

    val httpSources = sources.filterValues { it is SourceConfig.Http }
        .mapValues { (_, value) -> value as SourceConfig.Http }

    // Install one rate limit provider for each source that declares a limit. See F-024.
    val limitedSources = httpSources.filterValues { it.rateLimit != null }
    if (limitedSources.isNotEmpty()) {
        install(RateLimit) {
            limitedSources.forEach { (sourceName, httpConfig) ->
                register(RateLimitName(sourceName)) {
                    rateLimiter(
                        limit = httpConfig.rateLimit!!.requestsPerMinute,
                        refillPeriod = 60.seconds
                    )
                }
            }
        }
    }

    routing {
        httpSources.forEach { (sourceName, httpConfig) ->
            val path = "${config.basePath}${httpConfig.path}"
            if (httpConfig.rateLimit != null) {
                rateLimit(RateLimitName(sourceName)) {
                    post(path) { handleInboxPost(config, sourceName, httpConfig, handler, authValidator) }
                }
            } else {
                post(path) { handleInboxPost(config, sourceName, httpConfig, handler, authValidator) }
            }
        }
    }
}

private suspend fun RoutingContext.handleInboxPost(
    config: InboxConfig,
    sourceName: String,
    httpConfig: SourceConfig.Http,
    handler: InboxHandler,
    authValidator: InboxAuthValidator
) {
    // Read the body under a hard cap, before any other body read. See F-023.
    val rawBody = call.receiveCappedBody(config.maxBodyBytes)
    if (rawBody == null) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            mapOf("error" to "Request body exceeds ${config.maxBodyBytes} bytes")
        )
        return
    }
    call.attributes.put(RawBodyKey, rawBody)

    // Validate authentication if configured
    httpConfig.auth?.let { authConfig ->
        when (val authResult = authValidator.validate(call.request, authConfig)) {
            is AuthResult.Failure -> {
                call.respond(authResult.statusCode, mapOf("error" to authResult.message))
                return
            }
            AuthResult.Success -> { /* continue processing */ }
        }
    }

    // Parse JSON payload from the capped body
    val payload: JsonElement = try {
        Json.parseToJsonElement(rawBody.decodeToString())
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
        return
    }

    when (val result = handler.handle(sourceName, httpConfig, payload)) {
        is InboxHandlerResult.Accepted ->
            call.respond(HttpStatusCode.OK, mapOf("messageId" to result.messageId.toString()))

        is InboxHandlerResult.Duplicate ->
            call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))

        is InboxHandlerResult.ExtractionFailed ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.reason))

        is InboxHandlerResult.TransformFailed ->
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "Transform failed: ${result.reason}"))

        is InboxHandlerResult.StorageFailed ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Storage failed"))
    }
}

/**
 * Read the request body, but never keep more than [maxBytes] bytes in memory.
 *
 * The function returns null if the body is larger than [maxBytes]. It rejects a declared
 * Content-Length that is too large before it reads one byte. It also counts the bytes of a
 * chunked body, and it stops as soon as the count passes the cap. See F-023.
 */
private suspend fun ApplicationCall.receiveCappedBody(maxBytes: Long): ByteArray? {
    val declaredLength = request.contentLength()
    if (declaredLength != null && declaredLength > maxBytes) {
        return null
    }

    val channel = receiveChannel()
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read == -1) break
        if (read == 0) {
            // readAvailable suspends while the body is still open, so a zero read means the
            // channel has no more data for now. Yield rather than spin, and stop at the end.
            if (channel.isClosedForRead) break
            kotlinx.coroutines.yield()
            continue
        }
        total += read
        if (total > maxBytes) {
            return null
        }
        collected.write(chunk, 0, read)
    }
    return collected.toByteArray()
}
