package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.datetime.Clock
import org.nxtspec.AdminConfig
import org.nxtspec.app.dto.ReplayErrorResponse
import org.nxtspec.app.dto.TransformContextDto
import org.nxtspec.app.dto.TransformTestRequest
import org.nxtspec.app.dto.TransformTestResponse
import org.nxtspec.auth.AuthResult
import org.nxtspec.auth.InboxAuthValidator
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.ReplayFilter
import org.nxtspec.repository.ReplayResponse
import org.nxtspec.transform.TransformContext
import org.nxtspec.transform.TransformEngine
import java.util.*

/**
 * Configures admin routes for testing and debugging transform expressions.
 *
 * F-034: the route exists only when 'admin.enabled' is true. Every request needs the configured
 * credentials. The caller-supplied timeout and the caller-supplied payload have an upper bound.
 */
fun Application.configureAdminRoutes(
    admin: AdminConfig,
    authValidator: InboxAuthValidator,
    transformEngine: TransformEngine,
    outboxRepository: OutboxRepositoryInterface
) {
    // F-034: the admin endpoint is remote compute, so it stays absent until an operator enables it.
    if (!admin.enabled) return

    // Defence in depth. App.kt calls the same guard before the server starts, so a second
    // caller of this function cannot register the route without authentication.
    requireAdminAuth(admin)

    routing {
        route("/admin") {
            /**
             * POST /admin/transform/test
             *
             * Test a JSONata transform expression against a sample payload.
             * Useful for debugging and validating expressions without sending actual messages.
             */
            post("/transform/test") {
                val authConfig = admin.auth
                if (authConfig != null) {
                    val result = authValidator.validate(call.request, authConfig)
                    if (result is AuthResult.Failure) {
                        // The message names the scheme only. It never names a credential value.
                        call.respond(
                            result.statusCode,
                            TransformTestResponse(success = false, error = result.message)
                        )
                        return@post
                    }
                }

                // F-034: read the body under a hard cap. A chunked request declares no length,
                // so a check on Content-Length alone lets an arbitrarily large body reach the
                // parser.
                val rawBody = call.receiveCappedBody(admin.maxPayloadBytes.toLong())
                if (rawBody == null) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        TransformTestResponse(
                            success = false,
                            error = "Request body exceeds ${admin.maxPayloadBytes} bytes"
                        )
                    )
                    return@post
                }

                val request = try {
                    adminJson.decodeFromString<TransformTestRequest>(rawBody.decodeToString())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        TransformTestResponse(
                            success = false,
                            error = "Invalid request: ${e.message}"
                        )
                    )
                    return@post
                }

                // Validate expression first
                transformEngine.validateExpression(request.expression).onFailure { error ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        TransformTestResponse(
                            success = false,
                            error = "Invalid expression: ${error.message}"
                        )
                    )
                    return@post
                }

                // Create mock context
                val context = TransformContext(
                    messageId = UUID.randomUUID(),
                    topic = request.mockTopic ?: "test.topic",
                    // The poller passes 0 on a first delivery, because `attempt` counts the
                    // failed deliveries. The test route must show the operator the same value.
                    attempt = 0,
                    timestamp = Clock.System.now(),
                    source = request.mockSource
                )

                // F-034: the caller cannot ask for an unbounded evaluation.
                val timeoutMs = minOf(request.timeoutMs ?: DEFAULT_TRANSFORM_TIMEOUT_MS, admin.maxTransformTimeoutMs)

                // Evaluate the expression
                val result = transformEngine.evaluate(
                    expression = request.expression,
                    payload = request.payload,
                    context = context,
                    timeoutMs = timeoutMs
                )

                result.fold(
                    onSuccess = { output ->
                        call.respond(
                            TransformTestResponse(
                                success = true,
                                result = output,
                                context = TransformContextDto(
                                    messageId = context.messageId.toString(),
                                    topic = context.topic,
                                    attempt = context.attempt,
                                    timestamp = context.timestamp.toString()
                                )
                            )
                        )
                    },
                    onFailure = { error ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            TransformTestResponse(
                                success = false,
                                error = error.message ?: "Transform evaluation failed"
                            )
                        )
                    }
                )
            }

            /**
             * POST /admin/replay
             *
             * Moves the outbox rows that match the request filter, and that sit in state 'sent'
             * or 'dead', back to state 'pending'. See F-096.
             */
            post("/replay") {
                val authConfig = admin.auth
                if (authConfig != null) {
                    val result = authValidator.validate(call.request, authConfig)
                    if (result is AuthResult.Failure) {
                        // The message names the scheme only. It never names a credential value.
                        call.respond(result.statusCode, ReplayErrorResponse(result.message))
                        return@post
                    }
                }

                val filter = try {
                    call.receive<ReplayFilter>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ReplayErrorResponse("Invalid request: ${e.message}")
                    )
                    return@post
                }

                if (filter.isEmpty()) {
                    // A replay of every row is never an accident. See F-096.
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ReplayErrorResponse("A replay needs at least one filter.")
                    )
                    return@post
                }

                val moved = outboxRepository.replay(filter)
                // The response reports the count before an operator can lose it. The log
                // records the filter, so an operator who loses the response can still find
                // what they did. See F-096.
                call.application.log.info("Replay moved {} row(s). Filter: {}", moved, filter)
                call.respond(ReplayResponse(moved))
            }
        }
    }
}

private const val DEFAULT_TRANSFORM_TIMEOUT_MS = 100L

/**
 * The JSON reader for the admin request. The route parses the capped bytes itself, so the body
 * never reaches the content negotiation plugin unbounded. See F-034.
 */
private val adminJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * Reads the request body, but never keeps more than [maxBytes] bytes in memory.
 *
 * The function returns null when the body is larger than the cap. It rejects a declared
 * Content-Length that is too large before it reads one byte, and it counts the bytes of a
 * chunked body. See F-034.
 */
private suspend fun io.ktor.server.application.ApplicationCall.receiveCappedBody(maxBytes: Long): ByteArray? {
    val declaredLength = request.contentLength()
    if (declaredLength != null && declaredLength > maxBytes) return null

    val channel = receiveChannel()
    val collected = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L

    while (true) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read == -1) break
        if (read == 0) {
            if (channel.isClosedForRead) break
            kotlinx.coroutines.yield()
            continue
        }
        total += read
        if (total > maxBytes) return null
        collected.write(chunk, 0, read)
    }

    return collected.toByteArray()
}
