package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.nxtspec.AdminConfig
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxMessage
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
    outboxRepository: OutboxRepositoryInterface,
    messageRouter: MessageRouter
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
                handleTransformTest(admin, authValidator, transformEngine)
            }

            /**
             * POST /admin/replay
             *
             * Moves the outbox rows that match the request filter, and that sit in state 'sent'
             * or 'dead', back to state 'pending'. See F-096.
             */
            post("/replay") {
                handleReplay(admin, authValidator, outboxRepository, messageRouter)
            }
        }
    }
}

/**
 * Handles POST /admin/transform/test. Pulled out of the route body so the route registration
 * function itself stays short.
 */
private suspend fun RoutingContext.handleTransformTest(
    admin: AdminConfig,
    authValidator: InboxAuthValidator,
    transformEngine: TransformEngine
) {
    val authConfig = admin.auth
    if (authConfig != null) {
        val result = authValidator.validate(call.request, authConfig)
        if (result is AuthResult.Failure) {
            // The message names the scheme only. It never names a credential value.
            call.respond(result.statusCode, TransformTestResponse(success = false, error = result.message))
            return
        }
    }

    // F-034: read the body under a hard cap. A chunked request declares no length, so a check
    // on Content-Length alone lets an arbitrarily large body reach the parser.
    val rawBody = call.receiveCappedBody(admin.maxPayloadBytes.toLong())
    if (rawBody == null) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            TransformTestResponse(success = false, error = "Request body exceeds ${admin.maxPayloadBytes} bytes")
        )
        return
    }

    val request = try {
        adminJson.decodeFromString<TransformTestRequest>(rawBody.decodeToString())
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.BadRequest,
            TransformTestResponse(success = false, error = "Invalid request: ${e.message}")
        )
        return
    }

    transformEngine.validateExpression(request.expression).onFailure { error ->
        call.respond(
            HttpStatusCode.BadRequest,
            TransformTestResponse(success = false, error = "Invalid expression: ${error.message}")
        )
        return
    }

    // The poller passes 0 on a first delivery, because `attempt` counts the failed deliveries.
    // The test route must show the operator the same value.
    val context = TransformContext(
        messageId = UUID.randomUUID(),
        topic = request.mockTopic ?: "test.topic",
        attempt = 0,
        timestamp = Clock.System.now(),
        source = request.mockSource
    )

    // F-034: the caller cannot ask for an unbounded evaluation.
    val timeoutMs = minOf(request.timeoutMs ?: DEFAULT_TRANSFORM_TIMEOUT_MS, admin.maxTransformTimeoutMs)

    val result = transformEngine.evaluate(
        expression = request.expression,
        payload = request.payload,
        context = context,
        timeoutMs = timeoutMs
    )

    respondWithTransformResult(result, context)
}

/** Responds with the outcome of a transform evaluation. */
private suspend fun RoutingContext.respondWithTransformResult(
    result: Result<kotlinx.serialization.json.JsonElement>,
    context: TransformContext
) {
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
                TransformTestResponse(success = false, error = error.message ?: "Transform evaluation failed")
            )
        }
    )
}

/**
 * Handles POST /admin/replay. See F-096. Pulled out of the route body so the route registration
 * function itself stays short.
 */
private suspend fun RoutingContext.handleReplay(
    admin: AdminConfig,
    authValidator: InboxAuthValidator,
    outboxRepository: OutboxRepositoryInterface,
    messageRouter: MessageRouter
) {
    val authConfig = admin.auth
    if (authConfig != null) {
        val result = authValidator.validate(call.request, authConfig)
        if (result is AuthResult.Failure) {
            // The message names the scheme only. It never names a credential value.
            call.respond(result.statusCode, ReplayErrorResponse(result.message))
            return
        }
    }

    // F-034/F-096: read the body under the same hard cap as /transform/test. An unbounded
    // `ids` list must never reach the parser.
    val rawBody = call.receiveCappedBody(admin.maxPayloadBytes.toLong())
    if (rawBody == null) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            ReplayErrorResponse("Request body exceeds ${admin.maxPayloadBytes} bytes")
        )
        return
    }

    val filter = try {
        adminJson.decodeFromString<ReplayFilter>(rawBody.decodeToString())
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ReplayErrorResponse("Invalid request: ${e.message}"))
        return
    }

    if (filter.isEmpty()) {
        // A replay of every row is never an accident. See F-096.
        call.respond(HttpStatusCode.BadRequest, ReplayErrorResponse("A replay needs at least one filter."))
        return
    }

    val moved = replayMoved(filter, outboxRepository, messageRouter)
    // The response reports the count before an operator can lose it. The log records the
    // filter, so an operator who loses the response can still find what they did. See F-096.
    call.application.log.info("Replay moved {} row(s). Filter: {}", moved, filter)
    call.respond(ReplayResponse(moved))
}

/**
 * Runs the replay for [filter], resolving a [ReplayFilter.destination] to a topic set first.
 * See F-096.
 */
private suspend fun replayMoved(
    filter: ReplayFilter,
    outboxRepository: OutboxRepositoryInterface,
    messageRouter: MessageRouter
): Long {
    val destination = filter.destination ?: return outboxRepository.replay(filter)

    // F-096. A destination resolves to a topic set in Kotlin, through the same router and the
    // same first-match rule the relay itself uses, so a replay can never disagree with the
    // relay about which destination a topic belongs to. The SQL stays a plain `IN` clause.
    val matchingTopics = outboxRepository.distinctTopics().filter { topic ->
        val sample = OutboxMessage(topic = topic, payload = JsonObject(emptyMap()))
        messageRouter.route(sample)?.destination?.let { destinationName(it) } == destination
    }

    // No topic currently in the table resolves to this destination. The replay must move
    // nothing, never fall through to moving everything.
    if (matchingTopics.isEmpty()) return 0L

    return outboxRepository.replay(filter.copy(destination = null, topics = matchingTopics))
}

private const val DEFAULT_TRANSFORM_TIMEOUT_MS = 100L

/**
 * The configured name of a destination. F-096. The sealed interface carries no shared `name`
 * property, so every concrete destination type is named here, once.
 */
private fun destinationName(destination: Destination): String = when (destination) {
    is Destination.Http -> destination.name
    is Destination.Kafka -> destination.name
    is Destination.Nats -> destination.name
    is Destination.RabbitMQ -> destination.name
}

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
