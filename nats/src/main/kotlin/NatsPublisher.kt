package org.nxtspec

import io.nats.client.Connection
import io.nats.client.impl.Headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nxtspec.metrics.MetricsCollectorInterface
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Publishes an outbox message to a NATS subject.
 *
 * With JetStream the publish waits for the broker acknowledgement, so the outbox marks the row
 * sent only after the message is durable. With core NATS there is no acknowledgement to wait
 * for: the publish is fire and forget, so a message can vanish without an error and the row is
 * still marked sent. That is why JetStream is the default.
 */
class NatsPublisher(
    private val connectionFactory: (Destination.Nats) -> Connection = ::createConnection,
    private val metricsCollector: MetricsCollectorInterface? = null
) : Publisher,
    AutoCloseable {

    private val connections = ConcurrentHashMap<String, Connection>()

    override fun supports(destination: Destination): Boolean = destination is Destination.Nats

    override suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext
    ): Result<Unit> {
        val dest = destination as? Destination.Nats
            ?: return Result.failure(IllegalArgumentException("Not a NATS destination"))

        val startTime = System.currentTimeMillis()
        return try {
            val connection = connections.getOrPut(dest.name) { connectionFactory(dest) }
            // The route wins over the configured subject, exactly as the AMQP routing key does.
            val subject = context.routingKey ?: dest.subject
            val body = message.payload.toString().toByteArray()
            val headers = buildHeaders(message, dest)

            withContext(Dispatchers.IO) {
                if (dest.jetStream) {
                    connection.jetStream()
                        .publishAsync(subject, headers, body)
                        .get(dest.timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    connection.publish(subject, headers, body)
                    // `flush` is the only confirmation core NATS offers, and it confirms that the
                    // server received the bytes, not that any consumer will ever read them.
                    connection.flush(Duration.ofMillis(dest.timeoutMs))
                }
            }
            metricsCollector?.recordPublishDuration(System.currentTimeMillis() - startTime, "nats")
            Result.success(Unit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(NatsPublishException("The publish to '${dest.name}' was interrupted"))
        } catch (e: Exception) {
            Result.failure(
                NatsPublishException(
                    "The publish to '${dest.name}' failed. Reason: ${ErrorSanitizer.sanitize(e)}"
                )
            )
        }
    }

    private fun buildHeaders(message: OutboxMessage, dest: Destination.Nats): Headers {
        val headers = Headers()
        (dest.headers + message.headers).forEach { (name, value) -> headers.add(name, value) }
        headers.add("x-message-id", message.id.toString())
        headers.add("x-topic", message.topic)
        headers.add("x-attempt", message.attempt.toString())
        message.key?.let { headers.add("x-message-key", it) }
        return headers
    }

    override fun close() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    companion object {
        fun createConnection(dest: Destination.Nats): Connection =
            connect(dest.servers, dest.username, dest.password, dest.token, dest.timeoutMs)
    }
}

class NatsPublishException(message: String) : RuntimeException(message)
