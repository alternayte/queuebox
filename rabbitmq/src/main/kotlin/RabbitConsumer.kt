package org.nxtspec

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nxtspec.logging.CORRELATION_ID_HEADER
import org.nxtspec.logging.LogKeys
import org.nxtspec.logging.MAX_CORRELATION_ID_LENGTH
import org.nxtspec.logging.logger
import org.nxtspec.logging.withLogContext
import org.nxtspec.metrics.InboxRejectionReason
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
    private val sourceTransform: TransformConfig? = null,
    /**
     * Stores one rejected message that is already dead, in ONE transaction.
     *
     * Third review gate, defect 1. A store in state 'pending' followed by a mark dead commits a
     * claimable row first, and the relay can forward the rejected payload before the mark runs.
     * This callback removes the window, because the row never exists in state 'pending'.
     */
    private val storeDeadMessage: (suspend (InboxMessage) -> InboxResult)? = null
) {
    private val log = logger<RabbitConsumer>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // F-018: an AMQP channel is not thread safe. One actor coroutine owns the channel and
    // performs every acknowledgement. The message coroutines only send a command.
    private val ackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var ackCommands: kotlinx.coroutines.channels.Channel<AckCommand>? = null

    @Volatile
    private var ackActor: kotlinx.coroutines.Job? = null

    @Volatile
    private var consumerTag: String? = null

    @Volatile
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
                    withLogContext(
                        LogKeys.SOURCE to config.sourceName,
                        LogKeys.CORRELATION_ID to extractCorrelationId(properties)
                    ) {
                        processMessage(envelope, properties, body)
                    }
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
            log.error("Acknowledging delivery {} failed. Reason: {}", command.deliveryTag, ErrorSanitizer.sanitize(e))
        }
    }

    private suspend fun sendAck(deliveryTag: Long) {
        send(AckCommand.Ack(deliveryTag), deliveryTag)
    }

    private suspend fun sendNack(deliveryTag: Long, requeue: Boolean) {
        send(AckCommand.Nack(deliveryTag, requeue), deliveryTag)
    }

    /**
     * Sends one acknowledgement command to the actor.
     *
     * The actor closes during the shutdown. A command that arrives after the close cannot reach
     * the broker, so the message is redelivered. The loss is logged, because a silent loss hides
     * a shutdown that is too short.
     */
    private suspend fun send(command: AckCommand, deliveryTag: Long) {
        val commands = ackCommands
        if (commands == null) {
            logDroppedAcknowledgement(deliveryTag)
            return
        }
        try {
            commands.send(command)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            logDroppedAcknowledgement(deliveryTag)
        }
    }

    private fun logDroppedAcknowledgement(deliveryTag: Long) {
        log.warn(
            "QueueBox dropped the acknowledgement of delivery {}, because the consumer stopped. " +
                "The broker redelivers the message, and the inbox deduplicates it.",
            deliveryTag
        )
    }

    private suspend fun processMessage(envelope: Envelope, properties: AMQP.BasicProperties, body: ByteArray) {
        try {
            val payload = json.parseToJsonElement(body.toString(Charsets.UTF_8))
            val messageId = UUID.randomUUID()

            // Extract idempotency key with fallback chain (from ORIGINAL payload):
            // 1. x-idempotency-key header
            // 2. JSONPath from payload
            // 3. messageId property
            // 4. A stable SHA-256 digest of the body
            val idempotencyKey = extractIdempotencyKey(properties, payload, body)

            // Extract optional aggregate ID with fallback to header (from ORIGINAL payload)
            val aggregateId = extractAggregateId(properties, payload)

            // Extract optional event type from header
            val eventType = properties.headers?.get("x-event-type")?.toString()

            // F-047: the identifier follows an AMQP message as well. A header name is case
            // sensitive in AMQP, so both spellings are accepted.
            val correlationId = extractCorrelationId(properties)

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
                        // The message is preserved, not destroyed. The original payload reaches
                        // the inbox, and the row becomes dead.
                        val rejected = InboxMessage(
                            id = messageId,
                            source = config.sourceName,
                            idempotencyKey = idempotencyKey,
                            aggregateId = aggregateId,
                            eventType = eventType,
                            payload = payload,
                            correlationId = correlationId
                        )
                        storeRejected(envelope, rejected, result.reason)
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
                payload = transformedPayload,
                correlationId = correlationId
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
                    log.error(
                        "Storing delivery {} failed. The message is requeued. Reason: {}",
                        envelope.deliveryTag,
                        result.message
                    )
                    sendNack(envelope.deliveryTag, true)
                }
            }
        } catch (e: Exception) {
            log.error(
                "Processing delivery {} failed. The message is requeued. Reason: {}",
                envelope.deliveryTag,
                ErrorSanitizer.sanitize(e)
            )
            sendNack(envelope.deliveryTag, true)
        }
    }

    /**
     * Preserves a message that the transform rejected.
     *
     * The row is stored in state 'dead' in one transaction, so it is never claimable. The order
     * is mandatory. The row must be durable before the acknowledgement. A failed store therefore
     * ends in a nack with requeue, and the broker keeps the message.
     */
    private suspend fun storeRejected(envelope: Envelope, message: InboxMessage, reason: String) {
        log.warn(
            "The transform rejected delivery {}. QueueBox stores the original payload and marks " +
                "the row dead. Reason: {}",
            envelope.deliveryTag,
            reason
        )

        val storeDead = storeDeadMessage
        if (storeDead == null) {
            log.error(
                "No dead-letter store is configured for source '{}'. QueueBox requeues delivery " +
                    "{} rather than store a rejected payload that the relay can forward.",
                config.sourceName,
                envelope.deliveryTag
            )
            sendNack(envelope.deliveryTag, true)
            return
        }

        val result = try {
            storeDead(message)
        } catch (e: Exception) {
            InboxResult.Error(e.message ?: e::class.simpleName ?: "unknown")
        }

        when (result) {
            is InboxResult.Stored -> {
                // The row is already dead. No second statement is needed, and no window exists
                // in which the relay can claim the row.
                metricsCollector?.recordInboxRejection(InboxRejectionReason.TRANSFORM_FAILED)
                sendAck(envelope.deliveryTag)
            }
            is InboxResult.Duplicate -> {
                // The unique index already holds a row for this key, so the delivery adds
                // nothing. QueueBox marks nothing here.
                //
                // Fourth review gate, defect 1. An earlier mark by the natural key destroyed a
                // healthy row. The natural key is not unique to one delivery, because two events
                // can carry the same value in the field that `idempotencyKeyPath` names. The
                // mark is also unnecessary. A rejected message reaches `storeDeadMessage` only,
                // and that call writes state 'dead' in ONE transaction. No code path leaves a
                // rejected message in state 'pending', so no row needs repair here.
                metricsCollector?.recordInboxDuplicate()
                sendAck(envelope.deliveryTag)
            }
            is InboxResult.Error -> {
                log.error(
                    "Storing the rejected delivery {} failed. The message is requeued. Reason: {}",
                    envelope.deliveryTag,
                    result.message
                )
                sendNack(envelope.deliveryTag, true)
            }
        }
    }

    /**
     * Reads the correlation identifier of an AMQP delivery, or generates one. See F-047.
     *
     * The value reaches a log line, a database column, and an outbound header, so it is bounded
     * and it carries no control character.
     */
    private fun extractCorrelationId(properties: AMQP.BasicProperties): String {
        val fromHeader = properties.headers?.entries
            ?.firstOrNull { it.key.equals(CORRELATION_ID_HEADER, ignoreCase = true) }
            ?.value?.toString()

        return (fromHeader ?: properties.correlationId)
            ?.filter { !it.isISOControl() }
            ?.take(MAX_CORRELATION_ID_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
    }

    private fun extractIdempotencyKey(
        properties: AMQP.BasicProperties,
        payload: kotlinx.serialization.json.JsonElement,
        body: ByteArray
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

        // Priority 4: a stable digest of the body.
        //
        // A random value would give a redelivery of the identical message a new key, and the
        // inbox would hold a second row. The digest makes the key a function of the message
        // alone, so a redelivery deduplicates. The source is not part of the digest, because
        // the inbox key is the pair (source, idempotencyKey), and the source column already
        // separates two sources that carry the same body.
        return bodyDigest(body)
    }

    /** Returns the hexadecimal SHA-256 digest of the raw message body. */
    private fun bodyDigest(body: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
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
                log.warn("Cancelling the consumer tag {} failed. Reason: {}", tag, ErrorSanitizer.sanitize(e))
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

        // The message jobs cannot send another command after this point.
        scope.cancel()

        ackCommands?.close()
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { ackActor?.join() }
        ackCommands = null
        ackScope.cancel()
        ackActor = null

        try {
            openChannel?.close()
        } catch (e: Exception) {
            log.warn("Closing the AMQP channel failed. Reason: {}", ErrorSanitizer.sanitize(e))
        }
        channel = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 30_000L
    }
}
