package org.nxtspec

/**
 * Context that the outbox poller passes to a publisher for one message.
 *
 * @property routingKey The routing key that the route resolved for this message. A null value
 *   means the route sets no routing key, and the destination applies its own fallback. See F-004.
 * @property resolvedAddress The exchange, topic, or subject that the route resolved for this
 *   message from the destination's address template. F-091. The router is the single place that
 *   renders an address, for a RabbitMQ, Kafka, or NATS destination. Each of those publishers
 *   treats an empty value as a resolution failure: it fails the row and never falls back to the
 *   destination's own configured exchange, topic, or subject. An HTTP destination has no address
 *   template, so this value is always empty for one, and `HttpPublisher` never reads it: it
 *   builds its URL from the destination's own `baseUrl` and `path` instead.
 * @property resolvedDestinationRoutingKey The destination's own routing key template, already
 *   rendered against this row. A RabbitMQ destination uses this value as the routing key when
 *   the route itself sets no routing key. The router is the single place that renders it, so a
 *   publisher never renders a routing key template itself. Null for a destination type that
 *   carries no destination-level routing key template.
 */
data class PublishContext(
    val routingKey: String? = null,
    val resolvedAddress: String = "",
    val resolvedDestinationRoutingKey: String? = null
)

interface Publisher {
    suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext = PublishContext()
    ): Result<Unit>

    fun supports(destination: Destination): Boolean
}
