package org.nxtspec

/**
 * Context that the outbox poller passes to a publisher for one message.
 *
 * @property routingKey The routing key that the route resolved for this message. A null value
 *   means the route sets no routing key, and the destination applies its own fallback. See F-004.
 * @property resolvedAddress The exchange, topic, or subject that the route resolved for this
 *   message from the destination's address template. F-091. The router is the single place that
 *   renders an address. Every publisher treats an empty value as a resolution failure: it fails
 *   the row and never falls back to the destination's own configured exchange, topic, or subject.
 */
data class PublishContext(val routingKey: String? = null, val resolvedAddress: String = "")

interface Publisher {
    suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext = PublishContext()
    ): Result<Unit>

    fun supports(destination: Destination): Boolean
}
