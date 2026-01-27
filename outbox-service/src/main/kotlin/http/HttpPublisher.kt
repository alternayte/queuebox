package org.nxtspec.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.nxtspec.Destination
import org.nxtspec.OutboxMessage
import org.nxtspec.Publisher
import java.util.concurrent.ConcurrentHashMap

class HttpPublisher : Publisher {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    private fun getClient(destination: Destination.Http): HttpClient {
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
            }
        }
    }

    override fun supports(destination: Destination): Boolean = destination is Destination.Http

    override suspend fun publish(message: OutboxMessage, destination: Destination): Result<Unit> {
        val httpDest = destination as? Destination.Http
            ?: return Result.failure(IllegalArgumentException("HttpPublisher only supports HTTP destinations"))

        return try {
            val client = getClient(httpDest)
            val response = client.post(httpDest.baseUrl + httpDest.path) {
                contentType(ContentType.Application.Json)
                // Standard headers
                header("X-Message-Id", message.id.toString())
                header("X-Topic", message.topic)
                header("X-Attempt", message.attempt.toString())
                message.key?.let { header("X-Message-Key", it) }
                // Custom headers from config
                httpDest.headers.forEach { (k, v) -> header(k, v) }
                setBody(message.payload)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(
                    HttpPublishException(
                        message = "HTTP ${response.status.value}: ${response.status.description}",
                        statusCode = response.status.value,
                        body = response.bodyAsText()
                    )
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            Result.failure(HttpPublishException("Request timeout after ${httpDest.timeoutMs}ms", cause = e))
        } catch (e: Exception) {
            Result.failure(HttpPublishException("Publish failed: ${e.message}", cause = e))
        }
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

object HttpPublisherFactory {
    fun create(): HttpPublisher = HttpPublisher()
}
