package org.nxtspec

import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)

    // States considered "completed" and eligible for cleanup
    private val outboxCompletedStates = listOf("sent", "dead")
    private val inboxCompletedStates = listOf("processed")

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
                    val startTime = System.nanoTime()
                    val deleted = cleanup(tableConfig)
                    val durationNanos = System.nanoTime() - startTime

                    metricsCollector?.recordCleanupRun(table, deleted, durationNanos)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("Cleanup error for $table: ${e.message}")
                }
                delay(interval.inWholeMilliseconds)
            }
        }
    }

    private suspend fun cleanupOutbox(tableConfig: TableRetentionConfig): Int {
        return when (tableConfig.policy) {
            RetentionPolicy.AGE -> {
                val maxAge = DurationParser.parse(tableConfig.maxAge!!)
                val cutoff = Clock.System.now() - maxAge
                deleteInBatches(tableConfig.batchSize) { state ->
                    outboxRepository.deleteOlderThan(state, cutoff)
                }
            }
            RetentionPolicy.COUNT -> {
                // Count-based deletes all at once per state
                outboxCompletedStates.sumOf { state ->
                    outboxRepository.deleteExceptMostRecent(state, tableConfig.maxCount!!)
                }
            }
            RetentionPolicy.DISABLED -> 0
        }
    }

    private suspend fun cleanupInbox(tableConfig: TableRetentionConfig): Int {
        return when (tableConfig.policy) {
            RetentionPolicy.AGE -> {
                val maxAge = DurationParser.parse(tableConfig.maxAge!!)
                val cutoff = Clock.System.now() - maxAge
                deleteInBatches(tableConfig.batchSize) { state ->
                    inboxRepository.deleteOlderThan(state, cutoff)
                }
            }
            RetentionPolicy.COUNT -> {
                // Inbox only supports age-based retention
                println("Warning: COUNT retention policy not supported for inbox, skipping")
                0
            }
            RetentionPolicy.DISABLED -> 0
        }
    }

    /**
     * Deletes records in batches to prevent lock contention.
     * Keeps deleting until fewer than batchSize records are deleted.
     */
    private suspend fun deleteInBatches(
        batchSize: Int,
        deleteFn: suspend (state: String) -> Int
    ): Int {
        var totalDeleted = 0
        for (state in outboxCompletedStates) {
            var deleted: Int
            do {
                deleted = deleteFn(state)
                totalDeleted += deleted
                if (deleted > 0) {
                    // Small yield to prevent lock contention
                    delay(10)
                }
            } while (deleted >= batchSize)
        }
        return totalDeleted
    }

    fun isRunning(): Boolean = running.get()

    suspend fun stop() {
        running.set(false)
        // Wait for in-flight cleanup to complete
        scope.coroutineContext.job.children.forEach { it.join() }
        scope.cancel()
    }
}
