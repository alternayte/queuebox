package org.nxtspec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.Clock
import org.nxtspec.logging.CORRELATION_ID_HEADER
import org.nxtspec.logging.LogKeys
import org.nxtspec.logging.logger
import org.nxtspec.logging.withLogContext
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.TransactionRunner
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Moves a stored inbox message into the outbox table. See F-002.
 *
 * QueueBox forwards an inbox message onward. It runs no business logic on the message, because
 * it does not know the intent of the message. The outbox machinery then routes, transforms and
 * delivers the message.
 *
 * The outbox insert and the inbox mark run in one transaction. If the transaction fails, the
 * inbox row stays in state 'processing', and the reclaim step of F-006 returns it to state
 * 'pending'. Forwarding is therefore at least once, which the outbox idempotency key bounds.
 *
 * @property config The relay configuration
 * @property inboxRepository The inbox repository
 * @property outboxRepository The outbox repository
 * @property transactionRunner Runs the insert and the mark in one transaction
 * @property sourceTopicTemplates Topic template per source name
 * @property metricsCollector Optional metrics collector
 */
class InboxRelay(
    private val config: InboxRelayConfig,
    private val inboxRepository: InboxRepositoryInterface,
    private val outboxRepository: OutboxRepositoryInterface,
    private val transactionRunner: TransactionRunner,
    private val sourceTopicTemplates: Map<String, String> = emptyMap(),
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    private val log = logger<InboxRelay>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)

    // F-006: the reclaim step runs at most once per claimTimeoutMs / 5.
    private val reclaimIntervalMs = (config.claimTimeoutMs / 5).coerceAtLeast(1)
    private var lastReclaimAtMs = 0L

    fun start() {
        if (!config.enabled) return
        running.set(true)

        scope.launch {
            while (running.get()) {
                try {
                    relayBatch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    metricsCollector?.recordInboxRelayError()
                    log.error(
                        "The inbox relay cycle failed. The next cycle retries. Reason: {}",
                        ErrorSanitizer.sanitize(e)
                    )
                }
                delay(config.pollIntervalMs)
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    suspend fun shutdown() {
        running.set(false)
        scope.coroutineContext.job.children.forEach { it.join() }
        scope.cancel()
    }

    /**
     * Runs one relay cycle. Returns the number of forwarded messages.
     */
    suspend fun relayBatch(): Int {
        reclaimStaleClaims()

        val messages = inboxRepository.claimPending(config.batchSize)
        var forwarded = 0

        messages.forEach { message ->
            val moved = withLogContext(
                LogKeys.MESSAGE_ID to message.id,
                LogKeys.SOURCE to message.source,
                LogKeys.CORRELATION_ID to message.correlationId
            ) {
                forward(message)
            }
            if (moved) forwarded++
        }

        return forwarded
    }

    private suspend fun reclaimStaleClaims() {
        val now = System.currentTimeMillis()
        if (now - lastReclaimAtMs < reclaimIntervalMs) return
        lastReclaimAtMs = now
        inboxRepository.reclaimStale(config.claimTimeoutMs.milliseconds)
    }

    private suspend fun forward(message: InboxMessage): Boolean {
        val topic = renderTopic(message)

        if (topic.isBlank()) {
            // The template rendered empty, so the message has no destination topic.
            log.error(
                "The topic template of source '{}' rendered empty for message {}. The message " +
                    "is dead. Set 'sources.{}.topic', or set the event type path.",
                message.source,
                message.id,
                message.source
            )
            inboxRepository.markDead(message.id)
            metricsCollector?.recordInboxRelayError()
            return false
        }

        // The transaction runs child coroutines. supervisorScope stops a failed transaction
        // from cancelling the relay loop.
        val outcome = supervisorScope {
            runCatching {
                transactionRunner.inTransaction {
                    outboxRepository.insert(toOutboxMessage(message, topic))
                    inboxRepository.markProcessed(message.id)
                }
            }
        }

        return outcome.fold(
            onSuccess = {
                metricsCollector?.recordInboxForwarded()
                true
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                // The row stays in state 'processing'. The reclaim step returns it to 'pending'.
                log.error(
                    "Forwarding inbox message {} of source '{}' failed. The reclaim step " +
                        "returns the row to pending. Reason: {}",
                    message.id,
                    message.source,
                    ErrorSanitizer.sanitize(error)
                )
                metricsCollector?.recordInboxRelayError()
                false
            }
        )
    }

    /**
     * Renders the outbox topic from the source template. Supports `{{ source }}` and
     * `{{ eventType }}`.
     */
    internal fun renderTopic(message: InboxMessage): String {
        val template = sourceTopicTemplates[message.source] ?: DEFAULT_TOPIC_TEMPLATE
        return template
            .replace("{{ source }}", message.source)
            .replace("{{source}}", message.source)
            .replace("{{ eventType }}", message.eventType ?: "")
            .replace("{{eventType}}", message.eventType ?: "")
            .trim()
    }

    private fun toOutboxMessage(message: InboxMessage, topic: String): OutboxMessage {
        val now = Clock.System.now()
        return OutboxMessage(
            id = UUID.randomUUID(),
            topic = topic,
            key = message.aggregateId,
            payload = message.payload,
            headers = buildMap {
                put("x-inbox-id", message.id.toString())
                put("x-source", message.source)
                put("x-idempotency-key", message.idempotencyKey)
                // F-047: the identifier travels on to the destination, because the publisher
                // forwards every message header.
                message.correlationId?.let { put(CORRELATION_ID_HEADER, it) }
            },
            // The configured 'outbox.maxAttempts' reaches the row. A row value that an adopter
            // writes himself wins, because QueueBox reads the column. See the third review
            // gate, defect 1.
            maxAttempts = config.maxAttempts ?: DEFAULT_MAX_ATTEMPTS,
            state = MessageState.Pending,
            scheduledAt = now,
            createdAt = now,
            updatedAt = now
        )
    }

    companion object {
        const val DEFAULT_TOPIC_TEMPLATE: String = "{{ eventType }}"

        /**
         * The ceiling that the relay uses when the configuration names none. It equals the
         * column default, so the behaviour does not change.
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
