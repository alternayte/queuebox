package org.nxtspec

import kotlinx.coroutines.*
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.concurrent.atomic.AtomicBoolean

class OutboxPoller(
    private val config: OutboxConfig,
    private val repository: OutboxRepositoryInterface,
    private val router: MessageRouter,
    private val publishers: List<Publisher>,
    private val retryStrategy: RetryStrategy,
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(true)

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

    private suspend fun processMessage(message: OutboxMessage) {
        val startTime = System.currentTimeMillis()

        val routingResult = router.route(message.topic)
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

        publisher.publish(message, routingResult.destination).fold(
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
