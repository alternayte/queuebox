package org.nxtspec

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
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.nxtspec.logging.CORRELATION_ID_HEADER
import org.nxtspec.logging.MAX_CORRELATION_ID_LENGTH
import org.nxtspec.logging.logger
import org.nxtspec.metrics.InboxRejectionReason
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.transform.InboxTransformContext
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
import java.time.Duration
import java.util.Properties
import java.util.UUID

data class KafkaConsumerConfig(
    val sourceName: String,
    val bootstrapServers: String,
    val topics: List<String>,
    val groupId: String,
    val consumption: String = "push",
    val idempotencyKeyPath: String = "$.id",
    val aggregateIdPath: String? = null,
    val eventTypePath: String? = null,
    val autoOffsetReset: String = "earliest",
    val maxPollRecords: Int = 100,
    val securityProtocol: String = "PLAINTEXT",
    val saslMechanism: String? = null,
    val saslUsername: String? = null,
    val saslPassword: Secret? = null
)

/**
 * Consumes Kafka records into the inbox.
 *
 * Kafka has no per-message acknowledgement. It has offsets, and an offset is a promise that
 * everything before it is done. The loop therefore keeps the promise honest:
 *
 * 1. Poll a batch.
 * 2. Process the records of one partition in offset order.
 * 3. Commit the offset after the inbox row committed, and only for the unbroken run of records
 *    that succeeded.
 * 4. On a failure, stop that partition at the failed record and seek back to it, so the next
 *    poll reads it again.
 *
 * A crash between the store and the commit replays the record. The unique constraint on
 * `(source, idempotency_key)` then rejects the repeat, so at-least-once delivery from the broker
 * becomes exactly one inbox row.
 *
 * The Kafka client is not thread safe, so one coroutine owns the consumer for its whole life.
 * `stop` uses `wakeup`, which is the one call the client allows from another thread.
 */
class KafkaInboxConsumer(
    private val storeMessage: suspend (InboxMessage) -> InboxResult,
    private val extractor: IdempotencyExtractor,
    private val config: KafkaConsumerConfig,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val transformPipeline: InboxTransformPipeline? = null,
    private val sourceTransform: TransformConfig? = null,
    /** Stores a message that the transform rejected, already dead, in ONE transaction. */
    private val storeDeadMessage: (suspend (InboxMessage) -> InboxResult)? = null,
    private val consumerFactory: (KafkaConsumerConfig) -> Consumer<String, ByteArray> = ::createConsumer
) {
    private val log = logger<KafkaInboxConsumer>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var consumer: Consumer<String, ByteArray>? = null

    @Volatile private var loop: Job? = null

    @Volatile private var running = false

    /** True while the poll loop owns a subscribed consumer. The readiness answer reads it. */
    val isRunning: Boolean get() = running

    fun start() {
        check(loop == null) { "The consumer of source '${config.sourceName}' already started" }
        loop = scope.launch {
            val client = consumerFactory(config)
            consumer = client
            try {
                client.subscribe(config.topics)
                running = true
                while (isActive) {
                    val records = client.poll(Duration.ofMillis(POLL_TIMEOUT_MS))
                    if (records.isEmpty) continue
                    consumeBatch(client, records.partitions().associateWith { records.records(it) })
                }
            } catch (e: WakeupException) {
                // `stop` asked the loop to leave. This is the documented way out of a poll.
            } catch (e: Exception) {
                log.error(
                    "The consumer of source '{}' stopped. Reason: {}",
                    config.sourceName,
                    ErrorSanitizer.sanitize(e)
                )
            } finally {
                running = false
                runCatching {
                    client.close(
                        org.apache.kafka.clients.consumer.CloseOptions.timeout(Duration.ofMillis(CLOSE_TIMEOUT_MS))
                    )
                }
                consumer = null
            }
        }
    }

    /**
     * Processes one poll batch, one partition at a time, and commits what succeeded.
     *
     * The commit carries `offset + 1`, which is what Kafka defines as the next record to read.
     */
    private suspend fun consumeBatch(
        client: Consumer<String, ByteArray>,
        byPartition: Map<TopicPartition, List<ConsumerRecord<String, ByteArray>>>
    ) {
        val commits = mutableMapOf<TopicPartition, OffsetAndMetadata>()
        for ((partition, records) in byPartition) {
            for (record in records) {
                val done = handleRecord(record)
                if (!done) {
                    // Leave this partition at the failed record. Everything before it is
                    // committed, and the next poll reads this record again.
                    client.seek(partition, record.offset())
                    break
                }
                commits[partition] = OffsetAndMetadata(record.offset() + 1)
            }
        }
        if (commits.isNotEmpty()) {
            runCatching { client.commitSync(commits) }.onFailure {
                // A failed commit replays the batch. The inbox rejects the repeat, so the only
                // cost is the work of storing it again.
                log.warn(
                    "The offset commit of source '{}' failed. The next poll replays the batch, " +
                        "and the inbox rejects every repeat. Reason: {}",
                    config.sourceName,
                    ErrorSanitizer.sanitize(it)
                )
            }
        }
    }

    /** Returns true when the record is done and its offset may be committed. */
    private suspend fun handleRecord(record: ConsumerRecord<String, ByteArray>): Boolean {
        return try {
            val body = record.value() ?: ByteArray(0)
            val payload = parsePayload(body) ?: return storeUnparsable(record, body)

            val correlationId = extractCorrelationId(record)
            val message = InboxMessage(
                consumption = config.consumption,
                id = UUID.randomUUID(),
                source = config.sourceName,
                idempotencyKey = extractIdempotencyKey(record, payload, body),
                aggregateId = extractAggregateId(record, payload),
                eventType = extractEventType(record, payload),
                payload = payload,
                correlationId = correlationId
            )

            val transformed = applyTransform(message) ?: return true
            store(transformed)
        } catch (e: Exception) {
            log.error(
                "Processing the record at offset {} of '{}' failed. The next poll reads it " +
                    "again. Reason: {}",
                record.offset(),
                record.topic(),
                ErrorSanitizer.sanitize(e)
            )
            false
        }
    }

    /**
     * Applies the source transform. Returns null when the transform rejected the message, which
     * `storeRejected` has already stored as dead.
     */
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

    private suspend fun store(message: InboxMessage): Boolean = when (val result = storeMessage(message)) {
        is InboxResult.Stored -> {
            metricsCollector?.recordInboxReceived()
            true
        }
        is InboxResult.Duplicate -> {
            metricsCollector?.recordInboxDuplicate()
            true
        }
        is InboxResult.Error -> {
            log.error(
                "Storing a record of source '{}' failed. The next poll reads it again. Reason: {}",
                config.sourceName,
                result.message
            )
            false
        }
    }

    /**
     * Stores a message that the transform rejected, in state 'dead', in one transaction.
     *
     * The row is never claimable, so the relay cannot forward a rejected payload.
     */
    private suspend fun storeRejected(message: InboxMessage, reason: String) {
        val storeDead = storeDeadMessage
        if (storeDead == null) {
            log.error(
                "No dead-letter store is configured for source '{}', so QueueBox stores the " +
                    "rejected message as it arrived rather than drop it.",
                config.sourceName
            )
            store(message)
            return
        }
        log.warn(
            "The transform rejected a record of source '{}'. QueueBox stores the original " +
                "payload and marks the row dead. Reason: {}",
            config.sourceName,
            reason
        )
        runCatching { storeDead(message) }
            .onSuccess { metricsCollector?.recordInboxRejection(InboxRejectionReason.TRANSFORM_FAILED) }
            .onFailure {
                log.error(
                    "Storing the rejected record of source '{}' failed. Reason: {}",
                    config.sourceName,
                    ErrorSanitizer.sanitize(it)
                )
            }
    }

    /**
     * A record whose body is not JSON cannot become an inbox payload.
     *
     * Nothing downstream can read it, and leaving the offset uncommitted would stop the whole
     * partition for ever. The body is preserved as a string inside a JSON object and the row is
     * stored dead, so an operator can still see what arrived.
     */
    private suspend fun storeUnparsable(record: ConsumerRecord<String, ByteArray>, body: ByteArray): Boolean {
        val message = InboxMessage(
            consumption = config.consumption,
            id = UUID.randomUUID(),
            source = config.sourceName,
            idempotencyKey = bodyDigest(body),
            payload = JsonObject(mapOf("raw" to JsonPrimitive(body.decodeToString()))),
            correlationId = extractCorrelationId(record)
        )
        log.warn(
            "The record at offset {} of '{}' is not JSON. QueueBox stores it dead and moves the " +
                "offset, because no consumer can read it and a stopped partition would block " +
                "every later record.",
            record.offset(),
            record.topic()
        )
        val storeDead = storeDeadMessage
        if (storeDead == null) return store(message)
        runCatching { storeDead(message) }
            .onSuccess { metricsCollector?.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED) }
        return true
    }

    private fun parsePayload(body: ByteArray): JsonElement? = try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (e: Exception) {
        null
    }

    private fun header(record: ConsumerRecord<String, ByteArray>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.decodeToString()

    private fun extractCorrelationId(record: ConsumerRecord<String, ByteArray>): String =
        header(record, CORRELATION_ID_HEADER)
            ?.filter { !it.isISOControl() }
            ?.take(MAX_CORRELATION_ID_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

    /**
     * The record header wins, then the body path, then the record key, then a digest of the body.
     *
     * The digest is the last resort for the same reason as the AMQP source: a random value would
     * give a replay of the identical record a new key, and the inbox would hold a second row.
     */
    private fun extractIdempotencyKey(
        record: ConsumerRecord<String, ByteArray>,
        payload: JsonElement,
        body: ByteArray
    ): String {
        header(record, "x-idempotency-key")?.let { return it }
        val extracted = extractor.extract(payload, config.idempotencyKeyPath)
        if (extracted.isSuccess) return extracted.getOrThrow()
        record.key()?.takeIf { it.isNotBlank() }?.let { return it }
        return bodyDigest(body)
    }

    private fun extractEventType(record: ConsumerRecord<String, ByteArray>, payload: JsonElement): String? {
        config.eventTypePath?.let { path ->
            val extracted = extractor.extract(payload, path)
            if (extracted.isSuccess) return extracted.getOrThrow()
        }
        return header(record, "x-event-type")
    }

    private fun extractAggregateId(record: ConsumerRecord<String, ByteArray>, payload: JsonElement): String? {
        config.aggregateIdPath?.let { path ->
            val extracted = extractor.extract(payload, path)
            if (extracted.isSuccess) return extracted.getOrThrow()
        }
        return header(record, "x-aggregate-id") ?: record.key()
    }

    private fun bodyDigest(body: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Leaves the poll loop, then waits for it.
     *
     * `wakeup` is the only consumer call that another thread may make. The loop closes the
     * consumer itself, which commits nothing further, so every record that this replica did not
     * finish is read again by whichever replica takes the partition.
     */
    suspend fun stop() {
        runCatching { consumer?.wakeup() }
        loop?.let { job ->
            withTimeoutOrNull(STOP_TIMEOUT_MS) { job.join() }
        }
        scope.cancel()
        loop = null
        running = false
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 500L
        private const val CLOSE_TIMEOUT_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 30_000L

        fun createConsumer(config: KafkaConsumerConfig): Consumer<String, ByteArray> {
            val properties = Properties().apply {
                setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
                setProperty(ConsumerConfig.GROUP_ID_CONFIG, config.groupId)
                setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                // The loop commits after the inbox row commits, so the client must never commit
                // on its own timer.
                setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.autoOffsetReset)
                setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.maxPollRecords.toString())
                applySecurity(
                    this,
                    config.securityProtocol,
                    config.saslMechanism,
                    config.saslUsername,
                    config.saslPassword
                )
            }
            return org.apache.kafka.clients.consumer.KafkaConsumer(properties)
        }
    }
}
