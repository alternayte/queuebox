package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import org.nxtspec.app.dto.TransformContextDto
import org.nxtspec.app.dto.TransformTestRequest
import org.nxtspec.app.dto.TransformTestResponse
import org.nxtspec.transform.TransformContext
import org.nxtspec.transform.TransformEngine
import java.util.*

/**
 * Configures admin routes for testing and debugging transform expressions.
 */
fun Application.configureAdminRoutes(transformEngine: TransformEngine) {
    routing {
        route("/admin") {
            /**
             * POST /admin/transform/test
             *
             * Test a JSONata transform expression against a sample payload.
             * Useful for debugging and validating expressions without sending actual messages.
             */
            post("/transform/test") {
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

                // Evaluate the expression
                val result = transformEngine.evaluate(
                    expression = request.expression,
                    payload = request.payload,
                    context = context,
                    timeoutMs = request.timeoutMs ?: 100
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
