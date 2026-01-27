package org.nxtspec

import com.rabbitmq.client.AMQP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class RabbitPublisher(
    private val connections: ConcurrentHashMap<String, RabbitConnection> = ConcurrentHashMap()
) : Publisher {

    override fun supports(destination: Destination): Boolean = destination is Destination.RabbitMQ

    override suspend fun publish(message: OutboxMessage, destination: Destination): Result<Unit> {
        val dest = destination as? Destination.RabbitMQ
            ?: return Result.failure(IllegalArgumentException("Not a RabbitMQ destination"))

        return try {
            val connection = connections.getOrPut(dest.name) { RabbitConnection(dest.url) }

            withContext(Dispatchers.IO) {
                val channel = connection.getChannel()
                try {
                    // Enable publisher confirms
                    channel.confirmSelect()

                    // Declare exchange idempotently
                    channel.exchangeDeclare(dest.exchange, dest.exchangeType, true)

                    // Render routing key from template
                    val routingKey = dest.routingKeyTemplate
                        .replace("{{ topic }}", message.topic)
                        .replace("{{topic}}", message.topic)

                    // Build message properties
                    val props = AMQP.BasicProperties.Builder()
                        .messageId(message.id.toString())
                        .contentType("application/json")
                        .headers(
                            mapOf(
                                "x-topic" to message.topic,
                                "x-attempt" to message.attempt
                            )
                        )
                        .build()

                    // Publish with mandatory flag
                    val payload = message.payload.toString().toByteArray(Charsets.UTF_8)
                    channel.basicPublish(dest.exchange, routingKey, true, props, payload)

                    // Wait for confirm
                    if (!channel.waitForConfirms(5000)) {
                        return@withContext Result.failure(RabbitPublishException("Publish not confirmed"))
                    }

                    Result.success(Unit)
                } finally {
                    channel.close()
                }
            }
        } catch (e: Exception) {
            Result.failure(RabbitPublishException("RabbitMQ publish failed: ${e.message}", e))
        }
    }

    suspend fun close() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
