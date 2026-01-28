package org.nxtspec.repository

import kotlinx.datetime.Instant
import org.nxtspec.OutboxMessage
import java.util.UUID

/**
 * Interface for outbox repository operations.
 * Enables database-agnostic implementations for multi-database support.
 */
interface OutboxRepositoryInterface {
    /**
     * Claims a batch of pending messages for processing.
     * Atomically selects and marks messages as processing.
     */
    suspend fun claimBatch(batchSize: Int): List<OutboxMessage>

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
     * Deletes messages in the given state older than the cutoff time.
     * @return the number of deleted records
     */
    suspend fun deleteOlderThan(state: String, cutoff: Instant): Int

    /**
     * Deletes all messages in the given state except the most recent N.
     * @return the number of deleted records
     */
    suspend fun deleteExceptMostRecent(state: String, keepCount: Int): Int
}
