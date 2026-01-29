package org.nxtspec

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.nxtspec.auth.AuthResult
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.auth.RawBodyKey

fun Application.configureInboxRoutes(
    config: InboxConfig,
    sources: Map<String, SourceConfig>,
    handler: InboxHandler,
    authValidator: InboxAuthValidator = InboxAuthValidator()
) {
    // Install DoubleReceive for body buffering (needed for HMAC verification)
    install(DoubleReceive) {
        cacheRawRequest = true
    }

    routing {
        // Only set up HTTP routes for HTTP sources
        sources.filterValues { it is SourceConfig.Http }
            .forEach { (sourceName, sourceConfig) ->
            val httpConfig = sourceConfig as SourceConfig.Http
            post("${config.basePath}${httpConfig.path}") {
                // Validate authentication if configured
                httpConfig.auth?.let { authConfig ->
                    // For HMAC auth, we need to store the raw body for signature verification
                    if (authConfig is InboxAuthConfig.HmacSignature) {
                        val rawBody = call.receive<ByteArray>()
                        call.attributes.put(RawBodyKey, rawBody)
                    }

                    when (val authResult = authValidator.validate(call.request, authConfig)) {
                        is AuthResult.Failure -> {
                            call.respond(authResult.statusCode, mapOf("error" to authResult.message))
                            return@post
                        }
                        AuthResult.Success -> { /* continue processing */ }
                    }
                }

                // Parse JSON payload
                val payload = try {
                    // If we already read the body for HMAC, parse from the cached bytes
                    val rawBody = call.attributes.getOrNull(RawBodyKey)
                    if (rawBody != null) {
                        Json.parseToJsonElement(rawBody.decodeToString())
                    } else {
                        call.receive<JsonElement>()
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
                    return@post
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
        }
    }
}
