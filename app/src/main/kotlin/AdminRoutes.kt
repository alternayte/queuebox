package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.nxtspec.AdminConfig
import org.nxtspec.app.dto.TransformContextDto
import org.nxtspec.app.dto.TransformTestRequest
import org.nxtspec.app.dto.TransformTestResponse
import org.nxtspec.auth.AuthResult
import org.nxtspec.auth.InboxAuthValidator
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
    transformEngine: TransformEngine
) {
    // F-034: the admin endpoint is remote compute, so it stays absent until an operator enables it.
    if (!admin.enabled) return

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

                val declaredLength = call.request.contentLength()
                if (declaredLength != null && declaredLength > admin.maxPayloadBytes) {
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
                    call.receive<TransformTestRequest>()
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

                val payloadBytes = request.payload.toString().toByteArray(Charsets.UTF_8).size
                if (payloadBytes > admin.maxPayloadBytes) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        TransformTestResponse(
                            success = false,
                            error = "Payload exceeds ${admin.maxPayloadBytes} bytes"
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
                    attempt = 1,
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
        }
    }
}

private const val DEFAULT_TRANSFORM_TIMEOUT_MS = 100L
