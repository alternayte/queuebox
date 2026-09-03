package org.nxtspec

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.transform.TransformContext
import org.nxtspec.transform.TransformPipeline
import org.nxtspec.transform.TransformResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class OutboxPoller(
    private val config: OutboxConfig,
    private val repository: OutboxRepositoryInterface,
    private val router: MessageRouter,
    private val publishers: List<Publisher>,
    private val retryStrategy: RetryStrategy,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val transformPipeline: TransformPipeline? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(true)

    // F-015: the moment of the last pending count query.
    private var lastPendingGaugeAtMs = 0L

    // Number of messages that the poller currently publishes. Reported on a shutdown timeout.
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    // F-006: the reclaim step runs at most once per claimTimeoutMs / 5.
    private val reclaimIntervalMs = (config.claimTimeoutMs / 5).coerceAtLeast(1)
    private var lastReclaimAtMs = 0L

    fun start() {
        scope.launch {
            while (running.get()) {
                try {
                    processBatch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log error but continue polling
                    println("Polling error: ${e.message}")
                }
                delay(config.pollIntervalMs)
            }
        }
    }

    private suspend fun processBatch() {
        reclaimStaleClaims()

        val messages = repository.claimBatch(config.batchSize)

        updatePendingGauge()

        if (messages.isEmpty()) return

        // F-014: publish up to `concurrency` messages at the same time. One slow destination no
        // longer stalls the whole batch.
        val semaphore = Semaphore(config.concurrency)
        coroutineScope {
            messages.forEach { message ->
                launch {
                    semaphore.withPermit {
                        // F-013: one failing message must not abort the rest of the batch.
                        processMessageSafely(message)
                    }
                }
            }
        }
    }

    /**
     * F-015: the pending count feeds a gauge only, so it runs at most once per
     * `outbox.pendingGaugeIntervalMs`.
     */
    private suspend fun updatePendingGauge() {
        val collector = metricsCollector ?: return
        val now = System.currentTimeMillis()
        if (now - lastPendingGaugeAtMs < config.pendingGaugeIntervalMs) return
        lastPendingGaugeAtMs = now
        collector.updatePendingCount(repository.countByState("pending"))
    }

    /**
     * F-013: isolates one message. An exception from the repository, the router or the transform
     * applies the retry strategy to that message only, and the batch continues.
     */
    private suspend fun processMessageSafely(message: OutboxMessage) {
        inFlight.incrementAndGet()
        try {
            processMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metricsCollector?.recordProcessError()
            runCatching { handlePublishFailure(message, e) }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * F-006: returns messages that a crashed replica left in state 'processing' back to
     * state 'pending', so they are delivered after the restart.
     */
    private suspend fun reclaimStaleClaims() {
        val now = System.currentTimeMillis()
        if (now - lastReclaimAtMs < reclaimIntervalMs) return
        lastReclaimAtMs = now

        val reclaimed = repository.reclaimStale(config.claimTimeoutMs.milliseconds)
        if (reclaimed > 0) {
            metricsCollector?.recordMessageReclaimed(reclaimed)
        }
    }

    private suspend fun processMessage(message: OutboxMessage) {
        val startTime = System.currentTimeMillis()

        val routingResult = router.route(message.topic, message.payload)
        if (routingResult == null) {
            // No route found, mark as dead
            repository.markDead(message.id, "No route matches topic '${message.topic}'")
            metricsCollector?.recordMessageDead()
            recordProcessingDuration(startTime)
            return
        }

        val publisher = publishers.find { it.supports(routingResult.destination) }
        if (publisher == null) {
            // No publisher supports this destination, mark as dead
            repository.markDead(
                message.id,
                "No publisher supports destination '${routingResult.destination}'"
            )
            metricsCollector?.recordMessageDead()
            recordProcessingDuration(startTime)
            return
        }

        // Apply transforms if pipeline is configured
        val messageToPublish = if (transformPipeline != null &&
            (routingResult.routeTransform != null || routingResult.destinationTransform != null)
        ) {
            val context = TransformContext(
                messageId = message.id,
                topic = message.topic,
                attempt = message.attempt,
                timestamp = message.createdAt
            )

            when (val result = transformPipeline.transform(
                payload = message.payload,
                routeTransform = routingResult.routeTransform,
                destinationTransform = routingResult.destinationTransform,
                context = context
            )) {
                is TransformResult.Success -> message.copy(payload = result.payload)
                is TransformResult.Error -> {
                    handlePublishFailure(message, TransformException(result.message))
                    recordProcessingDuration(startTime)
                    return
                }
                is TransformResult.DeadLetter -> {
                    repository.markDead(message.id, "Transform dead-lettered the message")
                    metricsCollector?.recordMessageDead()
                    recordProcessingDuration(startTime)
                    return
                }
            }
        } else {
            message
        }

        publisher.publish(
            messageToPublish,
            routingResult.destination,
            PublishContext(routingKey = routingResult.routingKey)
        ).fold(
            onSuccess = {
                repository.markSent(message.id)
                metricsCollector?.recordMessageSent()
            },
            onFailure = { error ->
                handlePublishFailure(message, error)
            }
        )
        recordProcessingDuration(startTime)
    }

    private fun recordProcessingDuration(startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        metricsCollector?.recordProcessingDuration(duration)
    }

    /**
     * F-016: persists why the delivery failed. F-017: `scheduleRetry` is the only method that
     * increments the attempt count.
     */
    private suspend fun handlePublishFailure(message: OutboxMessage, error: Throwable) {
        val lastError = ErrorSanitizer.sanitize(error)
        if (retryStrategy.shouldRetry(message.attempt, message.maxAttempts)) {
            val delay = retryStrategy.calculateDelay(message.attempt)
            repository.scheduleRetry(message.id, delay, lastError)
            metricsCollector?.recordMessageFailed()
        } else {
            repository.markDead(message.id, lastError)
            metricsCollector?.recordMessageDead()
        }
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Stops the poll loop.
     *
     * F-028: the wait for the in-flight messages is bounded by `outbox.shutdownTimeoutMs`. A
     * message that is still in flight after the timeout stays in state 'processing', and the
     * F-006 reclaim returns it to 'pending'.
     */
    suspend fun shutdown() {
        running.set(false)
        val finished = withTimeoutOrNull(config.shutdownTimeoutMs) {
            scope.coroutineContext.job.children.forEach { it.join() }
            true
        }
        if (finished == null) {
            val abandoned = inFlight.get()
            println(
                "Shutdown timeout of ${config.shutdownTimeoutMs}ms elapsed. " +
                    "Abandoned $abandoned in-flight message(s). The reclaim step recovers them."
            )
        }
        scope.cancel()
    }
}

/**
 * Exception thrown when a payload transformation fails.
 */
class TransformException(message: String) : RuntimeException(message)
