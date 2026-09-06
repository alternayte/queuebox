package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.nxtspec.metrics.MetricsCollectorInterface
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Publishes an outbox message to a Kafka topic.
 *
 * One producer per destination name, cached. A Kafka producer is thread safe and batches
 * internally, so it needs no lock and no channel per message, unlike the AMQP publisher.
 *
 * The publish waits for the broker acknowledgement before it reports success, because the outbox
 * marks the row sent on that success. `acks=all` makes the acknowledgement mean that every
 * in-sync replica holds the record, so a broker failure after the mark does not lose the message.
 */
class KafkaPublisher(
    private val producerFactory: (Destination.Kafka) -> Producer<String, ByteArray> = ::createProducer,
    private val metricsCollector: MetricsCollectorInterface? = null
) : Publisher,
    AutoCloseable {

    private val producers = ConcurrentHashMap<String, Producer<String, ByteArray>>()

    override fun supports(destination: Destination): Boolean = destination is Destination.Kafka

    override suspend fun publish(
        message: OutboxMessage,
        destination: Destination,
        context: PublishContext
    ): Result<Unit> {
        val dest = destination as? Destination.Kafka
            ?: return Result.failure(IllegalArgumentException("Not a Kafka destination"))

        val startTime = System.currentTimeMillis()
        return try {
            val producer = producers.getOrPut(dest.name) { producerFactory(dest) }
            val record = buildRecord(message, dest, context)

            withContext(Dispatchers.IO) {
                // `get` with a timeout turns the producer future into the suspend contract that
                // the poller expects: a success only after the broker acknowledged.
                producer.send(record).get(dest.timeoutMs, TimeUnit.MILLISECONDS)
            }
            metricsCollector?.recordPublishDuration(System.currentTimeMillis() - startTime, "kafka")
            Result.success(Unit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(KafkaPublishException("The publish to '${dest.name}' was interrupted"))
        } catch (e: Exception) {
            // The message never carries the raw exception, because a Kafka client message can
            // repeat a SASL configuration value. The sanitiser keeps the type chain.
            Result.failure(
                KafkaPublishException(
                    "The publish to '${dest.name}' failed. Reason: ${ErrorSanitizer.sanitize(e)}"
                )
            )
        }
    }

    private fun buildRecord(
        message: OutboxMessage,
        dest: Destination.Kafka,
        context: PublishContext
    ): ProducerRecord<String, ByteArray> {
        // The route wins over the destination template, exactly as the AMQP routing key does.
        val key = context.routingKey
            ?: dest.keyTemplate
                .replace("{{ topic }}", message.topic)
                .replace("{{ key }}", message.key ?: "")
                .ifBlank { null }

        val record: ProducerRecord<String, ByteArray> =
            ProducerRecord(dest.topic, key, message.payload.toString().toByteArray())
        (dest.headers + message.headers).forEach { (name, value) ->
            record.headers().add(RecordHeader(name, value.toByteArray()))
        }
        record.headers().add(RecordHeader("x-message-id", message.id.toString().toByteArray()))
        record.headers().add(RecordHeader("x-topic", message.topic.toByteArray()))
        record.headers().add(RecordHeader("x-attempt", message.attempt.toString().toByteArray()))
        return record
    }

    override fun close() {
        producers.values.forEach { runCatching { it.close() } }
        producers.clear()
    }

    companion object {
        fun createProducer(dest: Destination.Kafka): Producer<String, ByteArray> {
            val properties = Properties().apply {
                setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, dest.bootstrapServers)
                setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                // Every in-sync replica must hold the record before the outbox marks the row sent.
                setProperty(ProducerConfig.ACKS_CONFIG, "all")
                setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                // Kafka requires `delivery.timeout.ms >= linger.ms + request.timeout.ms`. The
                // configured timeout is the whole budget, so the request timeout takes it minus
                // the batching wait, which is set to zero because the outbox already batches.
                setProperty(ProducerConfig.LINGER_MS_CONFIG, "0")
                setProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, dest.timeoutMs.toString())
                setProperty(
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    (dest.timeoutMs - REQUEST_TIMEOUT_MARGIN_MS).coerceAtLeast(MIN_REQUEST_TIMEOUT_MS).toString()
                )
                applySecurity(this, dest.securityProtocol, dest.saslMechanism, dest.saslUsername, dest.saslPassword)
            }
            return KafkaProducer(properties)
        }

        /** Room that the delivery timeout keeps above the request timeout. */
        private const val REQUEST_TIMEOUT_MARGIN_MS = 1000L
        private const val MIN_REQUEST_TIMEOUT_MS = 1000L
    }
}

class KafkaPublishException(message: String) : RuntimeException(message)
