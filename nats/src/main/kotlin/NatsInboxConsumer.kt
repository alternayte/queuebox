package org.nxtspec

import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.logging.CORRELATION_ID_HEADER
import org.nxtspec.logging.MAX_CORRELATION_ID_LENGTH
import org.nxtspec.logging.logger
import org.nxtspec.metrics.InboxRejectionReason
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.transform.InboxTransformContext
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
import java.time.Duration
import java.util.UUID

data class NatsConsumerConfig(
    val sourceName: String,
    val servers: String,
    val stream: String,
    val durable: String,
    val filterSubject: String? = null,
    val consumption: String = "push",
    val idempotencyKeyPath: String = "$.id",
    val aggregateIdPath: String? = null,
    val eventTypePath: String? = null,
    val ackWaitMs: Long = 30000,
    val batchSize: Int = 100,
    val username: String? = null,
    val password: Secret? = null,
    val token: Secret? = null
)

/**
 * Consumes a NATS JetStream stream into the inbox.
 *
 * The source is JetStream only, and the consumer is durable and pull based. Every message is
 * acknowledged one at a time, and only after the inbox row commits:
 *
 * - a stored or duplicate message is acknowledged, so JetStream never sends it again,
 * - a failed store is negatively acknowledged, so JetStream redelivers it,
 * - a message that this replica never reaches stays unacknowledged, and JetStream redelivers it
 *   after `ackWaitMs`.
 *
 * A crash between the store and the acknowledgement redelivers the message. The unique
 * constraint on `(source, idempotency_key)` then rejects the repeat.
 */
class NatsInboxConsumer(
    private val storeMessage: suspend (InboxMessage) -> InboxResult,
    private val extractor: IdempotencyExtractor,
    private val config: NatsConsumerConfig,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val transformPipeline: InboxTransformPipeline? = null,
    private val sourceTransform: TransformConfig? = null,
    /** Stores a message that the transform rejected, already dead, in ONE transaction. */
    private val storeDeadMessage: (suspend (InboxMessage) -> InboxResult)? = null,
    private val connectionFactory: (NatsConsumerConfig) -> Connection = ::createConnection
) {
    private val log = logger<NatsInboxConsumer>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var connection: Connection? = null

    @Volatile private var subscription: JetStreamSubscription? = null

    @Volatile private var loop: Job? = null

    @Volatile private var running = false

    /** True while the fetch loop owns a subscription. The readiness answer reads it. */
    val isRunning: Boolean get() = running

    fun start() {
        check(loop == null) { "The consumer of source '${config.sourceName}' already started" }
        loop = scope.launch {
            val client = connectionFactory(config)
            connection = client
            try {
                val subscribed = subscribe(client)
                subscription = subscribed
                running = true
                while (isActive) {
                    val batch = subscribed.fetch(config.batchSize, Duration.ofMillis(FETCH_TIMEOUT_MS))
                    batch.forEach { message -> handleMessage(message) }
                }
            } catch (e: Exception) {
                log.error(
                    "The consumer of source '{}' stopped. Reason: {}",
                    config.sourceName,
                    ErrorSanitizer.sanitize(e)
                )
            } finally {
                running = false
                runCatching { subscription?.unsubscribe() }
                subscription = null
                runCatching { client.close() }
                connection = null
            }
        }
    }

    /**
     * Binds a durable pull consumer to the configured stream.
     *
     * QueueBox never creates the stream. The retention, the replication and the storage of a
     * stream are an operator decision, and a stream that QueueBox invented would carry the
     * defaults of whichever version created it.
     */
    private fun subscribe(client: Connection): JetStreamSubscription {
        val consumerConfiguration = ConsumerConfiguration.Builder()
            .durable(config.durable)
            // Explicit acknowledgement is what lets the store decide the fate of a message.
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofMillis(config.ackWaitMs))
            .apply { config.filterSubject?.let { filterSubject(it) } }
            .build()

        val options = PullSubscribeOptions.Builder()
            .stream(config.stream)
            .configuration(consumerConfiguration)
            .build()

        return client.jetStream().subscribe(config.filterSubject ?: ">", options)
    }

    private suspend fun handleMessage(natsMessage: Message) {
        try {
            val body = natsMessage.data ?: ByteArray(0)
            val payload = parsePayload(body)
            if (payload == null) {
                storeUnreadable(natsMessage, body)
                return
            }

            val message = InboxMessage(
                consumption = config.consumption,
                id = UUID.randomUUID(),
                source = config.sourceName,
                idempotencyKey = extractIdempotencyKey(natsMessage, payload, body),
                aggregateId = extractAggregateId(natsMessage, payload),
                eventType = extractEventType(natsMessage, payload),
                payload = payload,
                correlationId = extractCorrelationId(natsMessage)
            )

            val transformed = applyTransform(message)
            if (transformed == null) {
                // The rejected message is already stored dead, so JetStream must not send it
                // again.
                natsMessage.ack()
                return
            }
            store(transformed, natsMessage)
        } catch (e: Exception) {
            log.error(
                "Processing a message of source '{}' failed. JetStream redelivers it. Reason: {}",
                config.sourceName,
                ErrorSanitizer.sanitize(e)
            )
            runCatching { natsMessage.nak() }
        }
    }

    private suspend fun applyTransform(message: InboxMessage): InboxMessage? {
        val pipeline = transformPipeline
        val transform = sourceTransform
        if (pipeline == null || transform == null) return message

        val context = InboxTransformContext(
            messageId = message.id,
            source = message.source,
            idempotencyKey = message.idempotencyKey,
            eventType = message.eventType,
            timestamp = Clock.System.now()
        )
        return when (val result = pipeline.transform(message.payload, transform, context)) {
            is InboxTransformResult.Success -> message.copy(payload = result.payload)
            is InboxTransformResult.Rejected -> {
                storeRejected(message, result.reason)
                null
            }
        }
    }

    private suspend fun store(message: InboxMessage, natsMessage: Message) {
        when (val result = storeMessage(message)) {
            is InboxResult.Stored -> {
                metricsCollector?.recordInboxReceived()
                natsMessage.ack()
            }
            is InboxResult.Duplicate -> {
                // The inbox already holds this key, so a redelivery would add nothing.
                metricsCollector?.recordInboxDuplicate()
                natsMessage.ack()
            }
            is InboxResult.Error -> {
                log.error(
                    "Storing a message of source '{}' failed. JetStream redelivers it. Reason: {}",
                    config.sourceName,
                    result.message
                )
                natsMessage.nak()
            }
        }
    }

    private suspend fun storeRejected(message: InboxMessage, reason: String) {
        val storeDead = storeDeadMessage
        if (storeDead == null) {
            log.error(
                "No dead-letter store is configured for source '{}', so QueueBox stores the " +
                    "rejected message as it arrived rather than drop it.",
                config.sourceName
            )
            runCatching { storeMessage(message) }
            return
        }
        log.warn(
            "The transform rejected a message of source '{}'. QueueBox stores the original " +
                "payload and marks the row dead. Reason: {}",
            config.sourceName,
            reason
        )
        runCatching { storeDead(message) }
            .onSuccess { metricsCollector?.recordInboxRejection(InboxRejectionReason.TRANSFORM_FAILED) }
            .onFailure {
                log.error(
                    "Storing the rejected message of source '{}' failed. Reason: {}",
                    config.sourceName,
                    ErrorSanitizer.sanitize(it)
                )
            }
    }

    /**
     * A message whose body is not JSON is stored dead and acknowledged.
     *
     * Nothing downstream can read it, and a message that is never acknowledged returns after
     * every `ackWaitMs` for ever.
     */
    private suspend fun storeUnreadable(natsMessage: Message, body: ByteArray) {
        val message = InboxMessage(
            consumption = config.consumption,
            id = UUID.randomUUID(),
            source = config.sourceName,
            idempotencyKey = bodyDigest(body),
            payload = JsonObject(mapOf("raw" to JsonPrimitive(body.decodeToString()))),
            correlationId = extractCorrelationId(natsMessage)
        )
        log.warn(
            "A message of source '{}' is not JSON. QueueBox stores it dead and acknowledges it, " +
                "because no consumer can read it and an unacknowledged message returns for ever.",
            config.sourceName
        )
        val storeDead = storeDeadMessage
        if (storeDead == null) {
            runCatching { storeMessage(message) }
        } else {
            runCatching { storeDead(message) }
                .onSuccess { metricsCollector?.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED) }
        }
        natsMessage.ack()
    }

    private fun parsePayload(body: ByteArray): JsonElement? = try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (e: Exception) {
        null
    }

    private fun header(natsMessage: Message, name: String): String? = natsMessage.headers?.getFirst(name)

    private fun extractCorrelationId(natsMessage: Message): String = header(natsMessage, CORRELATION_ID_HEADER)
        ?.filter { !it.isISOControl() }
        ?.take(MAX_CORRELATION_ID_LENGTH)
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()

    private fun extractIdempotencyKey(natsMessage: Message, payload: JsonElement, body: ByteArray): String {
        header(natsMessage, "x-idempotency-key")?.let { return it }
        val extracted = extractor.extract(payload, config.idempotencyKeyPath)
        if (extracted.isSuccess) return extracted.getOrThrow()
        // `Nats-Msg-Id` is the identifier that JetStream itself deduplicates on, so it is the
        // natural fallback before a digest of the body.
        header(natsMessage, "Nats-Msg-Id")?.let { return it }
        return bodyDigest(body)
    }

    private fun extractEventType(natsMessage: Message, payload: JsonElement): String? {
        config.eventTypePath?.let { path ->
            val extracted = extractor.extract(payload, path)
            if (extracted.isSuccess) return extracted.getOrThrow()
        }
        return header(natsMessage, "x-event-type")
    }

    private fun extractAggregateId(natsMessage: Message, payload: JsonElement): String? {
        config.aggregateIdPath?.let { path ->
            val extracted = extractor.extract(payload, path)
            if (extracted.isSuccess) return extracted.getOrThrow()
        }
        return header(natsMessage, "x-aggregate-id")
    }

    private fun bodyDigest(body: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Leaves the fetch loop, then waits for it.
     *
     * Every message that this replica did not acknowledge stays in the stream, and JetStream
     * delivers it again after `ackWaitMs`.
     */
    suspend fun stop() {
        loop?.cancel()
        loop?.let { job -> withTimeoutOrNull(STOP_TIMEOUT_MS) { job.join() } }
        runCatching { connection?.close() }
        scope.cancel()
        loop = null
        running = false
    }

    companion object {
        private const val FETCH_TIMEOUT_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 30_000L

        fun createConnection(config: NatsConsumerConfig): Connection =
            connect(config.servers, config.username, config.password, config.token, CONNECT_TIMEOUT_MS)

        private const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
