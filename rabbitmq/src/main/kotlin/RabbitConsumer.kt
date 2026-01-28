package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nxtspec.metrics.MetricsCollectorInterface
import java.util.UUID

@Serializable
data class RabbitConsumerConfig(
    val queueName: String,
    val sourceName: String,
    val prefetchCount: Int = 10,
    val idempotencyKeyPath: String = "$.id"
)

class RabbitConsumer(
    private val connection: RabbitConnection,
    private val storeMessage: suspend (InboxMessage) -> InboxResult,
    private val extractor: IdempotencyExtractor,
    private val config: RabbitConsumerConfig,
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var channel: Channel? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start() {
        channel = connection.getChannel().apply {
            basicQos(config.prefetchCount)
        }

        val consumer = object : DefaultConsumer(channel) {
            override fun handleDelivery(
                consumerTag: String,
                envelope: Envelope,
                properties: AMQP.BasicProperties,
                body: ByteArray
            ) {
                scope.launch {
                    processMessage(envelope, properties, body)
                }
            }
        }

        channel?.basicConsume(config.queueName, false, consumer)
    }

    private suspend fun processMessage(
        envelope: Envelope,
        properties: AMQP.BasicProperties,
        body: ByteArray
    ) {
        try {
            val payload = json.parseToJsonElement(body.toString(Charsets.UTF_8))

            // Extract idempotency key with fallback chain:
            // 1. x-idempotency-key header
            // 2. JSONPath from payload
            // 3. messageId property
            // 4. Generate UUID
            val idempotencyKey = extractIdempotencyKey(properties, payload)

            // Extract optional event type from header
            val eventType = properties.headers?.get("x-event-type")?.toString()

            val message = InboxMessage(
                source = config.sourceName,
                idempotencyKey = idempotencyKey,
                eventType = eventType,
                payload = payload
            )

            when (val result = storeMessage(message)) {
                is InboxResult.Stored -> {
                    metricsCollector?.recordInboxReceived()
                    channel?.basicAck(envelope.deliveryTag, false)
                }
                is InboxResult.Duplicate -> {
                    metricsCollector?.recordInboxDuplicate()
                    channel?.basicAck(envelope.deliveryTag, false)
                }
                is InboxResult.Error -> {
                    // Nack and requeue on storage failure
                    println("Storage error for message ${envelope.deliveryTag}: ${result.message}")
                    channel?.basicNack(envelope.deliveryTag, false, true)
                }
            }
        } catch (e: Exception) {
            println("Failed to process message ${envelope.deliveryTag}: ${e.message}")
            channel?.basicNack(envelope.deliveryTag, false, true)
        }
    }

    private fun extractIdempotencyKey(
        properties: AMQP.BasicProperties,
        payload: kotlinx.serialization.json.JsonElement
    ): String {
        // Priority 1: x-idempotency-key header
        val headerKey = properties.headers?.get("x-idempotency-key")
        if (headerKey != null) {
            return headerKey.toString()
        }

        // Priority 2: JSONPath extraction from payload
        val extracted = extractor.extract(payload, config.idempotencyKeyPath)
        if (extracted.isSuccess) {
            return extracted.getOrThrow()
        }

        // Priority 3: messageId property
        if (properties.messageId != null) {
            return properties.messageId
        }

        // Priority 4: Generate UUID as last resort
        return UUID.randomUUID().toString()
    }

    suspend fun stop() {
        scope.cancel()
        channel?.close()
        channel = null
    }
}
