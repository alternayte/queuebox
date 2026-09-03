package org.nxtspec.repository

import kotlinx.datetime.Instant
import org.nxtspec.OutboxMessage
import java.util.UUID
import kotlin.time.Duration

/**
 * Interface for outbox repository operations.
 * Enables database-agnostic implementations for multi-database support.
 */
interface OutboxRepositoryInterface {
    /**
     * Claims a batch of pending messages for processing.
     * Atomically selects and marks messages as processing, oldest first.
     */
    suspend fun claimBatch(batchSize: Int): List<OutboxMessage>

    /**
     * Inserts a message into the outbox in state 'pending'.
     *
     * The state of the given message is not persisted, because a new outbox row is always
     * pending. The inbox relay uses this method to forward a stored inbox message onward.
     */
    suspend fun insert(message: OutboxMessage)

    /**
     * Marks a message as successfully sent.
     */
    suspend fun markSent(id: UUID)

    /**
     * Marks a message as failed with an error description.
     */
    suspend fun markFailed(id: UUID, error: String)

    /**
     * Schedules a message for retry after a delay.
     */
    suspend fun scheduleRetry(id: UUID, delayMs: Long)

    /**
     * Marks a message as dead (exceeded max retries).
     */
    suspend fun markDead(id: UUID)

    /**
     * Counts messages in a given state.
     */
    suspend fun countByState(state: String): Long

    /**
     * Returns messages that stay in state 'processing' longer than the visibility timeout
     * back to state 'pending'. The attempt count does not change.
     * @return the number of reclaimed records
     */
    suspend fun reclaimStale(olderThan: Duration): Int

    /**
     * Deletes messages in the given state older than the cutoff time.
     * @param limit the maximum number of rows to delete in one statement
     * @return the number of deleted records
     */
    suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int

    /**
     * Deletes messages in the given state except the most recent N.
     * @param limit the maximum number of rows to delete in one statement
     * @return the number of deleted records
     */
    suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int
}
