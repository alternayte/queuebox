package org.nxtspec

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.nxtspec.logging.CORRELATION_ID_HEADER
import org.nxtspec.logging.LogKeys
import org.nxtspec.logging.logger
import org.nxtspec.logging.withLogContext
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
    private val log = logger<OutboxPoller>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(true)

    // F-015: the moment of the last pending count query.
    private var lastPendingGaugeAtMs = 0L

    // Number of messages that the poller currently publishes. Reported on a shutdown timeout.
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    // F-052: the number of messages that wait for a publish, per destination name.

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
                    log.error("The poll cycle failed. The next cycle retries. Reason: {}", ErrorSanitizer.sanitize(e))
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
            withLogContext(
                LogKeys.MESSAGE_ID to message.id,
                LogKeys.TOPIC to message.topic,
                LogKeys.ATTEMPT to message.attempt,
                // F-047: the identifier that the relay put in the headers reaches every log
                // line of the outbound publish.
                LogKeys.CORRELATION_ID to message.headers[CORRELATION_ID_HEADER]
            ) {
                processMessage(message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metricsCollector?.recordProcessError()
            // F-016: the throwable is not passed to the logger. SLF4J prints the raw message
            // and the raw stack trace, and a cause message can hold the broker URI with its
            // password. The sanitised chain names every type and every message, so the log
            // keeps enough information to debug. The frames are dropped, because they add no
            // safe information that the type chain does not already give.
            log.error(
                "Processing message {} failed. The retry strategy applies. Reason: {}",
                message.id,
                ErrorSanitizer.sanitize(e)
            )
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
            if (repository.markDead(message.id, message.claimedAt, "No route matches topic '${message.topic}'")) {
                metricsCollector?.recordMessageDead()
            } else {
                reportLostClaim(message, "mark the message dead, because no route matches the topic")
            }
            recordProcessingDuration(startTime)
            return
        }

        val publisher = publishers.find { it.supports(routingResult.destination) }
        if (publisher == null) {
            // No publisher supports this destination, mark as dead
            val won = repository.markDead(
                message.id,
                message.claimedAt,
                "No publisher supports destination '${routingResult.destination}'"
            )
            if (won) {
                metricsCollector?.recordMessageDead()
            } else {
                reportLostClaim(message, "mark the message dead, because no publisher supports the destination")
            }
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

            when (
                val result = transformPipeline.transform(
                    payload = message.payload,
                    routeTransform = routingResult.routeTransform,
                    destinationTransform = routingResult.destinationTransform,
                    context = context
                )
            ) {
                is TransformResult.Success -> message.copy(payload = result.payload)
                is TransformResult.Error -> {
                    handlePublishFailure(message, TransformException(result.message))
                    recordProcessingDuration(startTime)
                    return
                }
                is TransformResult.DeadLetter -> {
                    if (repository.markDead(message.id, message.claimedAt, "Transform dead-lettered the message")) {
                        metricsCollector?.recordMessageDead()
                    } else {
                        reportLostClaim(message, "mark the message dead after the transform dead-lettered it")
                    }
                    recordProcessingDuration(startTime)
                    return
                }
            }
        } else {
            message
        }

        // F-052: the depth counts the messages that wait for a publish to this destination.
        val destinationName = destinationName(routingResult.destination)
        metricsCollector?.changeQueueDepth(destinationName, 1)
        try {
            publisher.publish(
                messageToPublish,
                routingResult.destination,
                PublishContext(routingKey = routingResult.routingKey)
            ).fold(
                onSuccess = {
                    if (repository.markSent(message.id, message.claimedAt)) {
                        metricsCollector?.recordMessageSent()
                        metricsCollector?.recordDestinationSuccess(destinationName)
                    } else {
                        // Seventh review gate. The publish runs before the mark, so the
                        // destination already holds this message twice: this replica sent it,
                        // and the new owner of the claim sends it again. QueueBox cannot undo a
                        // delivery. It reports the duplicate and leaves the row to the new
                        // owner. A raised 'outbox.claimTimeoutMs' removes the cause.
                        log.error(
                            "Message {} was published, but the claim was already lost. Another " +
                                "replica owns the message and publishes it again, so the " +
                                "destination receives a duplicate. Raise 'outbox.claimTimeoutMs' " +
                                "above the slowest publish.",
                            message.id
                        )
                        metricsCollector?.recordClaimLost(OUTBOX_COMPONENT)
                    }
                },
                onFailure = { error ->
                    metricsCollector?.recordDestinationFailure(destinationName)
                    handlePublishFailure(message, error)
                }
            )
        } finally {
            metricsCollector?.changeQueueDepth(destinationName, -1)
        }
        recordProcessingDuration(startTime)
    }

    /**
     * F-052: the destination name comes from the configuration, so the label set stays bounded.
     */
    private fun destinationName(destination: Destination): String = when (destination) {
        is Destination.Http -> destination.name
        is Destination.RabbitMQ -> destination.name
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
            log.warn(
                "Publishing message {} failed on attempt {}. The retry runs in {} ms. Reason: {}",
                message.id,
                message.attempt,
                delay,
                lastError
            )
            if (repository.scheduleRetry(message.id, delay, message.claimedAt, lastError)) {
                metricsCollector?.recordMessageFailed()
            } else {
                reportLostClaim(message, "schedule the retry")
            }
        } else {
            log.error(
                "Message {} reached {} attempts and is now dead. Reason: {}",
                message.id,
                message.maxAttempts,
                lastError
            )
            if (repository.markDead(message.id, message.claimedAt, lastError)) {
                metricsCollector?.recordMessageDead()
            } else {
                reportLostClaim(message, "mark the message dead")
            }
        }
    }

    /**
     * Seventh review gate: reports a terminal write that lost the claim.
     *
     * The loss is not an error of this replica. The reclaim step returned the row to state
     * 'pending' on a timer, and another replica claimed it. This replica changes no state and
     * publishes nothing more. The new owner completes the message.
     */
    private fun reportLostClaim(message: OutboxMessage, action: String) {
        log.warn(
            "The claim on message {} was lost, so QueueBox did not {}. Another replica owns " +
                "the message now and completes it. Raise 'outbox.claimTimeoutMs' above the " +
                "slowest publish to stop the loss.",
            message.id,
            action
        )
        metricsCollector?.recordClaimLost(OUTBOX_COMPONENT)
    }

    fun isRunning(): Boolean = running.get()

    companion object {
        /** The metric label of this component. See `recordClaimLost`. */
        private const val OUTBOX_COMPONENT = "outbox"
    }

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
            log.warn(
                "The shutdown timeout of {} ms elapsed. QueueBox abandoned {} in-flight " +
                    "message(s). The reclaim step recovers them.",
                config.shutdownTimeoutMs,
                abandoned
            )
        }
        scope.cancel()
    }
}

/**
 * Exception thrown when a payload transformation fails.
 */
class TransformException(message: String) : RuntimeException(message)
