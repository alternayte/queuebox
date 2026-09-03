package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nxtspec.metrics.MetricsCollectorInterface
import java.util.concurrent.ConcurrentHashMap

class RabbitPublisher(
    private val connections: ConcurrentHashMap<String, RabbitConnection> = ConcurrentHashMap(),
    private val metricsCollector: MetricsCollectorInterface? = null
) : Publisher {

    /**
     * One cached confirm-enabled channel per destination. See F-020. A mutex serialises the
     * publish, because an AMQP channel is not thread safe. The set holds the message ids that
     * the broker returned as unroutable. See F-022.
     */
    private class DestinationChannel(val connection: RabbitConnection) {
        val mutex = Mutex()
        var channel: Channel? = null
        val returnedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val destinationChannels = ConcurrentHashMap<String, DestinationChannel>()

    override fun supports(destination: Destination): Boolean = destination is Destination.RabbitMQ

    override suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext
    ): Result<Unit> {
        val dest = destination as? Destination.RabbitMQ
            ?: return Result.failure(IllegalArgumentException("Not a RabbitMQ destination"))

        val startTime = System.currentTimeMillis()
        val holder = destinationChannels.getOrPut(dest.name) {
            DestinationChannel(connections.getOrPut(dest.name) { RabbitConnection(dest.url) })
        }

        return try {
            holder.mutex.withLock {
                withContext(Dispatchers.IO) {
                    val channel = openChannel(holder, dest)
                    val messageId = message.id.toString()
                    holder.returnedIds.remove(messageId)

                    // F-004: the routing key that the route resolved wins. The destination
                    // template is the fallback for a route that sets no routing key.
                    val routingKey = context.routingKey
                        ?: dest.routingKeyTemplate
                            .replace("{{ topic }}", message.topic)
                            .replace("{{topic}}", message.topic)

                    // Build merged headers: standard headers, then destination headers, then per-message headers
                    // Per-message headers take highest precedence and can override all others
                    val mergedHeaders = buildMap<String, Any> {
                        // Standard headers
                        put("x-topic", message.topic)
                        put("x-attempt", message.attempt)
                        // Destination-level static headers
                        dest.headers.forEach { (k, v) -> put(k, v) }
                        // Per-message dynamic headers (override destination headers)
                        message.headers.forEach { (k, v) -> put(k, v) }
                    }

                    // Build message properties
                    val props = AMQP.BasicProperties.Builder()
                        .messageId(messageId)
                        .contentType("application/json")
                        .headers(mergedHeaders)
                        .build()

                    try {
                        // Publish with mandatory flag
                        val payload = message.payload.toString().toByteArray(Charsets.UTF_8)
                        channel.basicPublish(dest.exchange, routingKey, true, props, payload)

                        // Wait for confirm
                        if (!channel.waitForConfirms(5000)) {
                            recordPublishDuration(startTime)
                            return@withContext Result.failure(
                                RabbitPublishException("Publish not confirmed")
                            )
                        }
                    } catch (e: Exception) {
                        discardChannel(holder)
                        throw e
                    }

                    // F-022: the broker returns an unroutable message before it confirms it.
                    // A returned message must fail, so the retry path runs.
                    if (holder.returnedIds.remove(messageId)) {
                        recordPublishDuration(startTime)
                        return@withContext Result.failure(
                            RabbitPublishException(
                                "Message $messageId is unroutable on exchange ${dest.exchange} " +
                                    "with routing key $routingKey"
                            )
                        )
                    }

                    recordPublishDuration(startTime)
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            recordPublishDuration(startTime)
            Result.failure(RabbitPublishException("RabbitMQ publish failed: ${e.message}", e))
        }
    }

    /**
     * Return the cached channel. Create it, enable confirms, declare the exchange once, and
     * register the return listener when the cache is empty or the channel is closed.
     */
    private suspend fun openChannel(
        holder: DestinationChannel,
        dest: Destination.RabbitMQ
    ): Channel {
        val cached = holder.channel
        if (cached != null && cached.isOpen) {
            return cached
        }
        val channel = holder.connection.getChannel()
        channel.confirmSelect()
        channel.exchangeDeclare(dest.exchange, dest.exchangeType, true)
        channel.addReturnListener { returnMessage ->
            returnMessage.properties?.messageId?.let { holder.returnedIds.add(it) }
        }
        holder.channel = channel
        return channel
    }

    private fun discardChannel(holder: DestinationChannel) {
        try {
            holder.channel?.close()
        } catch (_: Exception) {
            // The channel is already broken. The next publish creates a new one.
        }
        holder.channel = null
    }

    private fun recordPublishDuration(startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        metricsCollector?.recordPublishDuration(duration, "rabbitmq")
    }

    suspend fun close() {
        destinationChannels.values.forEach { holder ->
            holder.mutex.withLock { discardChannel(holder) }
        }
        destinationChannels.clear()
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
