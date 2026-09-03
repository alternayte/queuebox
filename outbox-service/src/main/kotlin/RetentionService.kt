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
                    println("Cleanup error for $table: ${e.message}")
                }
                delay(interval.inWholeMilliseconds)
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

    private suspend fun cleanupOutbox(tableConfig: TableRetentionConfig): Int {
        return when (tableConfig.policy) {
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
    }

    private suspend fun cleanupInbox(tableConfig: TableRetentionConfig): Int {
        return when (tableConfig.policy) {
            RetentionPolicy.AGE -> {
                val maxAge = DurationParser.parse(tableConfig.maxAge!!)
                val cutoff = Clock.System.now() - maxAge
                deleteInBatches(inboxCompletedStates, tableConfig.batchSize) { state, limit ->
                    inboxRepository.deleteOlderThan(state, cutoff, limit)
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

    fun isRunning(): Boolean = running.get()

    suspend fun stop() {
        running.set(false)
        // Wait for in-flight cleanup to complete
        scope.coroutineContext.job.children.forEach { it.join() }
        scope.cancel()
    }
}
