package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.transform.InboxTransformContext
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
import java.util.UUID

@Serializable
data class RabbitConsumerConfig(
    val queueName: String,
    val sourceName: String,
    val prefetchCount: Int = 10,
    val idempotencyKeyPath: String = "$.id",
    val aggregateIdPath: String? = null
)

private sealed interface AckCommand {
    val deliveryTag: Long

    data class Ack(override val deliveryTag: Long) : AckCommand
    data class Nack(override val deliveryTag: Long, val requeue: Boolean) : AckCommand
}

class RabbitConsumer(
    private val connection: RabbitConnection,
    private val storeMessage: suspend (InboxMessage) -> InboxResult,
    private val extractor: IdempotencyExtractor,
    private val config: RabbitConsumerConfig,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val transformPipeline: InboxTransformPipeline? = null,
    private val sourceTransform: TransformConfig? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // F-018: an AMQP channel is not thread safe. One actor coroutine owns the channel and
    // performs every acknowledgement. The message coroutines only send a command.
    private val ackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ackCommands: kotlinx.coroutines.channels.Channel<AckCommand>? = null
    private var ackActor: kotlinx.coroutines.Job? = null

    private var consumerTag: String? = null
    private var channel: Channel? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** True while the AMQP channel is open. Used by the tests for F-018. */
    val isChannelOpen: Boolean
        get() = channel?.isOpen == true

    suspend fun start() {
        val openChannel = connection.getChannel().apply {
            basicQos(config.prefetchCount)
        }
        channel = openChannel

        val commands = kotlinx.coroutines.channels.Channel<AckCommand>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        ackCommands = commands
        ackActor = ackScope.launch {
            for (command in commands) {
                applyAck(openChannel, command)
            }
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

        consumerTag = openChannel.basicConsume(config.queueName, false, consumer)
    }

    private fun applyAck(openChannel: Channel, command: AckCommand) {
        try {
            when (command) {
                is AckCommand.Ack -> openChannel.basicAck(command.deliveryTag, false)
                is AckCommand.Nack ->
                    openChannel.basicNack(command.deliveryTag, false, command.requeue)
            }
        } catch (e: Exception) {
            println("Failed to acknowledge delivery ${command.deliveryTag}: ${e.message}")
        }
    }

    private suspend fun sendAck(deliveryTag: Long) {
        ackCommands?.send(AckCommand.Ack(deliveryTag))
    }

    private suspend fun sendNack(deliveryTag: Long, requeue: Boolean) {
        ackCommands?.send(AckCommand.Nack(deliveryTag, requeue))
    }

    private suspend fun processMessage(
        envelope: Envelope,
        properties: AMQP.BasicProperties,
        body: ByteArray
    ) {
        try {
            val payload = json.parseToJsonElement(body.toString(Charsets.UTF_8))
            val messageId = UUID.randomUUID()

            // Extract idempotency key with fallback chain (from ORIGINAL payload):
            // 1. x-idempotency-key header
            // 2. JSONPath from payload
            // 3. messageId property
            // 4. Generate UUID
            val idempotencyKey = extractIdempotencyKey(properties, payload)

            // Extract optional aggregate ID with fallback to header (from ORIGINAL payload)
            val aggregateId = extractAggregateId(properties, payload)

            // Extract optional event type from header
            val eventType = properties.headers?.get("x-event-type")?.toString()

            // Apply transform if configured
            val transformedPayload = if (transformPipeline != null && sourceTransform != null) {
                val context = InboxTransformContext(
                    messageId = messageId,
                    source = config.sourceName,
                    idempotencyKey = idempotencyKey,
                    eventType = eventType,
                    timestamp = Clock.System.now()
                )
                when (val result = transformPipeline.transform(payload, sourceTransform, context)) {
                    is InboxTransformResult.Success -> result.payload
                    is InboxTransformResult.Rejected -> {
                        // NACK without requeue for transform rejection
                        println("Transform rejected message ${envelope.deliveryTag}: ${result.reason}")
                        sendNack(envelope.deliveryTag, false)
                        return
                    }
                }
            } else {
                payload
            }

            val message = InboxMessage(
                id = messageId,
                source = config.sourceName,
                idempotencyKey = idempotencyKey,
                aggregateId = aggregateId,
                eventType = eventType,
                payload = transformedPayload
            )

            when (val result = storeMessage(message)) {
                is InboxResult.Stored -> {
                    metricsCollector?.recordInboxReceived()
                    sendAck(envelope.deliveryTag)
                }
                is InboxResult.Duplicate -> {
                    metricsCollector?.recordInboxDuplicate()
                    sendAck(envelope.deliveryTag)
                }
                is InboxResult.Error -> {
                    // Nack and requeue on storage failure
                    println("Storage error for message ${envelope.deliveryTag}: ${result.message}")
                    sendNack(envelope.deliveryTag, true)
                }
            }
        } catch (e: Exception) {
            println("Failed to process message ${envelope.deliveryTag}: ${e.message}")
            sendNack(envelope.deliveryTag, true)
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

    private fun extractAggregateId(
        properties: AMQP.BasicProperties,
        payload: kotlinx.serialization.json.JsonElement
    ): String? {
        // Priority 1: JSONPath extraction from payload
        config.aggregateIdPath?.let { path ->
            val extracted = extractor.extract(payload, path)
            if (extracted.isSuccess) {
                return extracted.getOrThrow()
            }
        }

        // Priority 2: x-aggregate-id header fallback
        return properties.headers?.get("x-aggregate-id")?.toString()
    }

    /**
     * F-019: cancel the consumer tag first, then wait for the work that is already in flight,
     * then drain the acknowledgements, then close the channel. Every message that reached the
     * store is acknowledged before the channel closes.
     */
    suspend fun stop() {
        val openChannel = channel
        consumerTag?.let { tag ->
            try {
                openChannel?.basicCancel(tag)
            } catch (e: Exception) {
                println("Failed to cancel consumer $tag: ${e.message}")
            }
        }
        consumerTag = null

        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            // A delivery that the broker already sent can still start a job. Two passes join
            // the jobs that start while the first pass runs.
            repeat(2) {
                scope.coroutineContext.job.children.toList().forEach { it.join() }
            }
        }

        ackCommands?.close()
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { ackActor?.join() }
        ackCommands = null
        ackActor = null

        try {
            openChannel?.close()
        } catch (e: Exception) {
            println("Failed to close the channel: ${e.message}")
        }
        channel = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 30_000L
    }
}
