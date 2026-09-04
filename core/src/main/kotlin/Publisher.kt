package org.nxtspec

/**
 * Context that the outbox poller passes to a publisher for one message.
 *
 * @property routingKey The routing key that the route resolved for this message. A null value
 *   means the route sets no routing key, and the destination applies its own fallback. See F-004.
 */
data class PublishContext(val routingKey: String? = null)

interface Publisher {
    suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext = PublishContext()
    ): Result<Unit>

    fun supports(destination: Destination): Boolean
}
