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
                    println("Inbox relay error: ${e.message}")
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
            if (forward(message)) forwarded++
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
            headers = mapOf(
                "x-inbox-id" to message.id.toString(),
                "x-source" to message.source,
                "x-idempotency-key" to message.idempotencyKey
            ),
            state = MessageState.Pending,
            scheduledAt = now,
            createdAt = now,
            updatedAt = now
        )
    }

    companion object {
        const val DEFAULT_TOPIC_TEMPLATE: String = "{{ eventType }}"
    }
}
