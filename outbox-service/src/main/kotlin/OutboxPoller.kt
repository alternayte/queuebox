package org.nxtspec

import kotlinx.coroutines.*
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

        // Update pending count metric
        metricsCollector?.let {
            val pendingCount = repository.countByState("pending")
            it.updatePendingCount(pendingCount)
        }

        messages.forEach { message ->
            processMessage(message)
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
            repository.markDead(message.id)
            metricsCollector?.recordMessageDead()
            recordProcessingDuration(startTime)
            return
        }

        val publisher = publishers.find { it.supports(routingResult.destination) }
        if (publisher == null) {
            // No publisher supports this destination, mark as dead
            repository.markDead(message.id)
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
                    repository.markDead(message.id)
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

    private suspend fun handlePublishFailure(message: OutboxMessage, error: Throwable) {
        if (retryStrategy.shouldRetry(message.attempt, message.maxAttempts)) {
            val delay = retryStrategy.calculateDelay(message.attempt)
            repository.scheduleRetry(message.id, delay)
            metricsCollector?.recordMessageFailed()
        } else {
            repository.markDead(message.id)
            metricsCollector?.recordMessageDead()
        }
    }

    fun isRunning(): Boolean = running.get()

    suspend fun shutdown() {
        running.set(false)
        // Wait for in-flight processing to complete
        scope.coroutineContext.job.children.forEach { it.join() }
        scope.cancel()
    }
}

/**
 * Exception thrown when a payload transformation fails.
 */
class TransformException(message: String) : RuntimeException(message)
