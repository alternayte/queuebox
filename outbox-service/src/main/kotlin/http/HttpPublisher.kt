package org.nxtspec.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import org.nxtspec.Destination
import org.nxtspec.ErrorSanitizer
import org.nxtspec.HttpConfig
import org.nxtspec.OutboxMessage
import org.nxtspec.PublishContext
import org.nxtspec.Publisher
import org.nxtspec.auth.DestinationAuthResolver
import org.nxtspec.metrics.MetricsCollectorInterface
import java.util.concurrent.ConcurrentHashMap

class HttpPublisher(
    private val clientFactory: ((Destination.Http) -> HttpClient)? = null,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val authResolver: DestinationAuthResolver? = null,
    private val httpConfig: HttpConfig = HttpConfig()
) : Publisher {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    private fun getClient(destination: Destination.Http): HttpClient {
        // Use injected factory if provided (for testing), otherwise create CIO client
        clientFactory?.let { return it(destination) }

        return clients.getOrPut(destination.name) {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json()
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = destination.timeoutMs
                    connectTimeoutMillis = destination.timeoutMs / 2
                }
                expectSuccess = false // Don't throw on non-2xx
                // F-040: never follow a redirect. A validated public destination that answers
                // 302 with a private Location would otherwise reach a metadata address, with
                // the destination authentication headers attached. A 3xx now fails the publish
                // and the retry or dead-letter path runs.
                followRedirects = false
            }
        }
    }

    override fun supports(destination: Destination): Boolean = destination is Destination.Http

    override suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext
    ): Result<Unit> {
        val httpDest = destination as? Destination.Http
            ?: return Result.failure(IllegalArgumentException("HttpPublisher only supports HTTP destinations"))

        val startTime = System.currentTimeMillis()
        return try {
            val client = getClient(httpDest)

            // Resolve auth headers if configured
            val authHeaders = authResolver?.resolveAuthHeaders(httpDest.authConfig) ?: emptyMap()

            // Merge headers: destination static -> auth -> per-message (later wins)
            val mergedCustomHeaders = httpDest.headers + authHeaders + message.headers

            val response = client.post(joinUrl(httpDest.baseUrl, httpDest.path)) {
                contentType(ContentType.Application.Json)
                // Standard headers
                header("X-Message-Id", message.id.toString())
                header("X-Topic", message.topic)
                header("X-Attempt", message.attempt.toString())
                message.key?.let { header("X-Message-Key", it) }
                // Custom headers (destination + per-message, with per-message taking precedence)
                mergedCustomHeaders.forEach { (k, v) -> header(k, v) }
                setBody(message.payload)
            }

            recordPublishDuration(startTime)

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                // F-039: read at most maxErrorBodyBytes from the channel. bodyAsText reads the
                // whole body into memory first, so a hostile destination could allocate
                // megabytes per failing message.
                val safeBody = boundAndRedact(readBoundedErrorBody(response))
                val head = "HTTP ${response.status.value}: ${response.status.description}"
                val text = if (safeBody.isNullOrBlank()) head else "$head - $safeBody"
                Result.failure(
                    HttpPublishException(
                        message = text.take(httpConfig.maxErrorBodyBytes),
                        statusCode = response.status.value,
                        body = safeBody
                    )
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            recordPublishDuration(startTime)
            Result.failure(HttpPublishException("Request timeout after ${httpDest.timeoutMs}ms", cause = e))
        } catch (e: Exception) {
            recordPublishDuration(startTime)
            // F-039: a client exception message can carry the request URL, which can carry a
            // userinfo credential. The generic path is bounded and redacted as well.
            val safeMessage = boundAndRedact("Publish failed: ${e.message}") ?: "Publish failed"
            Result.failure(HttpPublishException(safeMessage, cause = e))
        }
    }

    /**
     * Reads at most `http.maxErrorBodyBytes` bytes of the error body.
     *
     * The read stops at the limit, so the process never holds the whole body of a hostile or a
     * broken destination. See F-039.
     */
    private suspend fun readBoundedErrorBody(response: io.ktor.client.statement.HttpResponse): String {
        val limit = httpConfig.maxErrorBodyBytes.toLong()
        val channel = response.bodyAsChannel()
        val packet = channel.readRemaining(limit)
        // The rest of the body is discarded. The connection closes with the response.
        return packet.readText()
    }

    /**
     * Joins the base URL and the path with a URL builder. See F-040.
     *
     * A missing slash or a duplicated slash therefore cannot change the target.
     */
    private fun joinUrl(baseUrl: String, path: String): String {
        val builder = URLBuilder(baseUrl)
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isNotEmpty()) {
            builder.appendPathSegments(segments)
        }
        return builder.buildString()
    }

    /**
     * Bounds and redacts the error body before it leaves the publisher. See F-039.
     *
     * A hostile or broken destination can return megabytes, and an error body often echoes the
     * request, which includes an authorization header. The publisher therefore truncates the body
     * to `http.maxErrorBodyBytes` and then redacts every secret value.
     */
    private fun boundAndRedact(body: String?): String? {
        if (body == null) return null
        val bounded = body.take(httpConfig.maxErrorBodyBytes)
        return ErrorSanitizer.sanitize(bounded)?.take(httpConfig.maxErrorBodyBytes)
    }

    private fun recordPublishDuration(startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        metricsCollector?.recordPublishDuration(duration, "http")
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

object HttpPublisherFactory {
    fun create(
        metricsCollector: MetricsCollectorInterface? = null,
        authResolver: DestinationAuthResolver? = null,
        httpConfig: HttpConfig = HttpConfig()
    ): HttpPublisher = HttpPublisher(
        metricsCollector = metricsCollector,
        authResolver = authResolver,
        httpConfig = httpConfig
    )
}
