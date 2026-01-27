package org.nxtspec

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonElement

fun Application.configureInboxRoutes(
    config: InboxConfig,
    sources: Map<String, SourceConfig>,
    handler: InboxHandler
) {
    routing {
        // Only set up HTTP routes for HTTP sources
        sources.filterValues { it is SourceConfig.Http }
            .forEach { (sourceName, sourceConfig) ->
            val httpConfig = sourceConfig as SourceConfig.Http
            post("${config.basePath}${httpConfig.path}") {
                val payload = try {
                    call.receive<JsonElement>()
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

                    is InboxHandlerResult.StorageFailed ->
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Storage failed"))
                }
            }
        }
    }
}
