package org.nxtspec

/**
 * Context that the outbox poller passes to a publisher for one message.
 *
 * @property routingKey The routing key that the route resolved for this message. A null value
 *   means the route sets no routing key, and the destination applies its own fallback. See F-004.
 * @property resolvedAddress The exchange, topic, or subject that the route resolved for this
 *   message from the destination's address template. F-091. The router is the single place that
 *   renders an address. The empty default is dead for Kafka and NATS, which ignore this field
 *   until their own task wires it in. The RabbitMQ publisher treats an empty value as a resolution
 *   failure and never falls back to it.
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
