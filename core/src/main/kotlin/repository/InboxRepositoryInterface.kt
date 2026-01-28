package org.nxtspec.repository

import kotlinx.datetime.Instant
import org.nxtspec.InboxMessage
import org.nxtspec.InboxResult
import java.util.UUID

/**
 * Interface for inbox repository operations.
 * Enables database-agnostic implementations for multi-database support.
 */
interface InboxRepositoryInterface {
    /**
     * Stores a message in the inbox with idempotency check.
     * @return InboxResult indicating whether the message was stored, was a duplicate, or encountered an error
     */
    suspend fun store(message: InboxMessage): InboxResult

    /**
     * Claims a batch of pending messages for processing.
     * Atomically selects and marks messages as processing.
     */
    suspend fun claimPending(batchSize: Int): List<InboxMessage>

    /**
     * Marks a message as processed.
     */
    suspend fun markProcessed(id: UUID)

    /**
     * Counts messages in a given state.
     */
    suspend fun countByState(state: String): Long

    /**
     * Deletes messages in the given state older than the cutoff time.
     * @return the number of deleted records
     */
    suspend fun deleteOlderThan(state: String, cutoff: Instant): Int
}
