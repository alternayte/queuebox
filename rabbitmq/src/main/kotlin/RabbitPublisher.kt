package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nxtspec.metrics.MetricsCollectorInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

        /**
         * The exchange names already declared on the current channel. F-091. A rendered exchange
         * can differ per row, so the declare-once behaviour that used to live in [openChannel]
         * moves here, keyed by name instead of by channel lifetime alone.
         */
        val declaredExchanges: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The number of exchangeDeclare calls actually issued on the current channel. F-091. */
        val exchangeDeclareCallCount: AtomicInteger = AtomicInteger(0)
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

        return try {
            // The connection constructor parses the URL, so it can throw. It runs inside the
            // try, so the failure becomes a sanitised Result.failure and does not escape
            // publish() with the raw AMQP URI. The cache behaviour does not change, because
            // getOrPut still creates one holder and one connection per destination name.
            val holder = destinationChannels.getOrPut(dest.name) {
                DestinationChannel(connections.getOrPut(dest.name) { RabbitConnection(dest.url) })
            }
            holder.mutex.withLock {
                withContext(Dispatchers.IO) {
                    val channel = openChannel(holder)
                    val exchange = resolveExchange(dest, context)
                    declareExchangeIfNeeded(holder, channel, dest, exchange)
                    val messageId = message.id.toString()
                    holder.returnedIds.remove(messageId)

                    // F-004: the routing key that the route resolved wins. The destination
                    // template is the fallback for a route that sets no routing key. F-091: the
                    // router renders that fallback against the row before it reaches this
                    // publisher, so `context.resolvedDestinationRoutingKey` is the value to use.
                    // The literal `{{ topic }}` replace below is a last-resort fallback only for
                    // a caller that bypasses MessageRouter and supplies no resolved value.
                    val routingKey = context.routingKey
                        ?: context.resolvedDestinationRoutingKey
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
                        channel.basicPublish(exchange, routingKey, true, props, payload)

                        // Wait for confirm
                        if (!channel.waitForConfirms(5000)) {
                            recordPublishDuration(startTime)
                            return@withContext Result.failure(
                                RabbitPublishException("Publish not confirmed")
                            )
                        }
                    } catch (e: Exception) {
                        // The broker can have recorded a return before the failure, so the id
                        // is removed here as well. Without it the set grows for the life of
                        // the process.
                        holder.returnedIds.remove(messageId)
                        discardChannel(holder)
                        throw e
                    }

                    // F-022: the broker returns an unroutable message before it confirms it.
                    // A returned message must fail, so the retry path runs.
                    if (holder.returnedIds.remove(messageId)) {
                        recordPublishDuration(startTime)
                        return@withContext Result.failure(
                            RabbitPublishException(
                                "Message $messageId is unroutable on exchange $exchange " +
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
     * Return the cached channel. Create it, enable confirms, and register the return listener
     * when the cache is empty or the channel is closed. F-091: the exchange is no longer
     * declared here, because the exchange can differ per row. [declareExchangeIfNeeded] declares
     * it once a row's exchange name is known.
     */
    private suspend fun openChannel(holder: DestinationChannel): Channel {
        val cached = holder.channel
        if (cached != null && cached.isOpen) {
            return cached
        }
        val channel = holder.connection.getChannel()
        channel.confirmSelect()
        channel.addReturnListener { returnMessage ->
            returnMessage.properties?.messageId?.let { holder.returnedIds.add(it) }
        }
        holder.channel = channel
        return channel
    }

    /**
     * Validates the exchange the router already resolved for this row. F-091. The router is the
     * single place that renders a destination's address template, so a publisher only checks the
     * result and never renders one itself.
     *
     * @throws RabbitPublishException when the rendered exchange is empty. An empty exchange name
     *   is the AMQP default exchange, which delivers straight to a queue named after the routing
     *   key. That delivery is silent and wrong, so the row must fail instead.
     */
    private fun resolveExchange(dest: Destination.RabbitMQ, context: PublishContext): String {
        val exchange = context.resolvedAddress
        if (exchange.isBlank()) {
            throw RabbitPublishException(
                "Destination '${dest.name}' rendered an empty exchange from template '${dest.exchange}'. " +
                    "QueueBox does not publish the message, and it never uses the AMQP default exchange."
            )
        }
        return exchange
    }

    /**
     * Declares one exchange name on the channel the first time this channel sees it. F-091. A
     * per-row exchange must not turn into a broker round trip on every publish, so the channel
     * keeps a set of the names it already declared.
     */
    private fun declareExchangeIfNeeded(
        holder: DestinationChannel,
        channel: Channel,
        dest: Destination.RabbitMQ,
        exchange: String
    ) {
        if (holder.declaredExchanges.add(exchange)) {
            channel.exchangeDeclare(exchange, dest.exchangeType, true)
            holder.exchangeDeclareCallCount.incrementAndGet()
        }
    }

    /**
     * The number of exchangeDeclare calls the publisher issued for one destination's current
     * channel. Exposed for tests. F-091.
     */
    fun declaredExchangeCount(destinationName: String): Int =
        destinationChannels[destinationName]?.exchangeDeclareCallCount?.get() ?: 0

    private fun discardChannel(holder: DestinationChannel) {
        try {
            holder.channel?.close()
        } catch (_: Exception) {
            // The channel is already broken. The next publish creates a new one.
        }
        holder.channel = null
        // A return that the discarded channel recorded cannot belong to a future publish.
        holder.returnedIds.clear()
        // A new channel must declare its exchanges again. F-091.
        holder.declaredExchanges.clear()
        holder.exchangeDeclareCallCount.set(0)
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
