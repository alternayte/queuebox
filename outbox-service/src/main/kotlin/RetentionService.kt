package org.nxtspec

import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.nxtspec.logging.logger
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that handles retention cleanup for outbox and inbox tables.
 * Runs as a coroutine-based scheduler executing age-based and count-based
 * retention policies without blocking normal operations.
 */
class RetentionService(
    private val config: RetentionConfig,
    private val outboxRepository: OutboxRepositoryInterface,
    private val inboxRepository: InboxRepositoryInterface,
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    private val log = logger<RetentionService>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)

    // States considered "completed" and eligible for cleanup
    private val outboxCompletedStates = listOf("sent", "dead")
    private val inboxCompletedStates = listOf("processed", "dead")

    fun start() {
        if (!config.enabled) return
        running.set(true)

        if (config.outbox.policy != RetentionPolicy.DISABLED) {
            scheduleCleanup("outbox", config.outbox) { cleanupOutbox(it) }
        }
        if (config.inbox.policy != RetentionPolicy.DISABLED) {
            scheduleCleanup("inbox", config.inbox) { cleanupInbox(it) }
        }
    }

    private fun scheduleCleanup(
        table: String,
        tableConfig: TableRetentionConfig,
        cleanup: suspend (TableRetentionConfig) -> Int
    ) {
        val interval = DurationParser.parse(tableConfig.cleanupInterval)

        scope.launch {
            while (running.get()) {
                try {
                    runCleanupOnce(table, tableConfig, cleanup)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(
                        "The retention cleanup of the {} table failed. Reason: {}",
                        table,
                        ErrorSanitizer.sanitize(e)
                    )
                }
                interruptibleDelay(interval.inWholeMilliseconds)
            }
        }
    }

    /** Runs one outbox cleanup cycle. The tests use this entry point. */
    suspend fun runOutboxCleanupOnce(): Int = runCleanupOnce("outbox", config.outbox) { cleanupOutbox(it) }

    /** Runs one inbox cleanup cycle. The tests use this entry point. */
    suspend fun runInboxCleanupOnce(): Int = runCleanupOnce("inbox", config.inbox) { cleanupInbox(it) }

    private suspend fun runCleanupOnce(
        table: String,
        tableConfig: TableRetentionConfig,
        cleanup: suspend (TableRetentionConfig) -> Int
    ): Int {
        val startTime = System.nanoTime()
        val deleted = cleanup(tableConfig)
        metricsCollector?.recordCleanupRun(table, deleted, System.nanoTime() - startTime)
        return deleted
    }

    private suspend fun cleanupOutbox(tableConfig: TableRetentionConfig): Int = when (tableConfig.policy) {
        RetentionPolicy.AGE -> {
            val maxAge = DurationParser.parse(tableConfig.maxAge!!)
            val cutoff = Clock.System.now() - maxAge
            deleteInBatches(outboxCompletedStates, tableConfig.batchSize) { state, limit ->
                outboxRepository.deleteOlderThan(state, cutoff, limit)
            }
        }
        RetentionPolicy.COUNT -> {
            deleteInBatches(outboxCompletedStates, tableConfig.batchSize) { state, limit ->
                outboxRepository.deleteExceptMostRecent(state, tableConfig.maxCount!!, limit)
            }
        }
        RetentionPolicy.DISABLED -> 0
    }

    private suspend fun cleanupInbox(tableConfig: TableRetentionConfig): Int = when (tableConfig.policy) {
        RetentionPolicy.AGE -> {
            val maxAge = DurationParser.parse(tableConfig.maxAge!!)
            val cutoff = Clock.System.now() - maxAge
            deleteInBatches(inboxCompletedStates, tableConfig.batchSize) { state, limit ->
                inboxRepository.deleteOlderThan(state, cutoff, limit)
            }
        }
        RetentionPolicy.COUNT -> {
            // The inbox repository has no count-based delete, so the policy deletes nothing.
            // A silent skip lets the table grow without bound. ConfigValidator rejects the
            // policy at startup. This branch guards a service that another caller builds.
            // See the third review gate, defect 2.
            error(
                "The inbox retention does not support the count policy. Use 'AGE' or " +
                    "'DISABLED' for 'retention.inbox.policy'."
            )
        }
        RetentionPolicy.DISABLED -> 0
    }

    /**
     * Deletes records in batches to prevent lock contention.
     *
     * F-007: the caller supplies the completed states of its own table. The outbox states and
     * the inbox states are different.
     * F-008: the caller supplies the batch size to the delete statement, so one statement never
     * deletes the whole table.
     */
    private suspend fun deleteInBatches(
        states: List<String>,
        batchSize: Int,
        deleteFn: suspend (state: String, limit: Int) -> Int
    ): Int {
        var totalDeleted = 0
        for (state in states) {
            var deleted: Int
            do {
                deleted = deleteFn(state, batchSize)
                totalDeleted += deleted
                if (deleted > 0) {
                    // Small yield to prevent lock contention
                    delay(10)
                }
            } while (deleted >= batchSize)
        }
        return totalDeleted
    }

    /**
     * Waits in short slices, so a stop does not have to wait for the whole cleanup interval.
     * See F-027.
     */
    private suspend fun interruptibleDelay(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0 && running.get()) {
            val slice = minOf(remaining, WAIT_SLICE_MS)
            delay(slice)
            remaining -= slice
        }
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Stops the cleanup loops.
     *
     * F-027: the wait is bounded. A loop that sits in its interval wakes within one wait slice.
     * A cleanup that runs longer than the timeout is cancelled.
     */
    suspend fun stop() {
        running.set(false)
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            scope.coroutineContext.job.children.forEach { it.join() }
        }
        scope.cancel()
    }

    companion object {
        private const val WAIT_SLICE_MS = 100L
        private const val STOP_TIMEOUT_MS = 1500L
    }
}
