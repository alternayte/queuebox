package org.nxtspec.repository

import kotlinx.datetime.Instant
import org.nxtspec.InboxMessage
import org.nxtspec.InboxResult
import java.util.UUID
import kotlin.time.Duration

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
     * Stores a message that is already dead, in ONE transaction, with the same idempotency check.
     *
     * Third review gate, defect 1. A store in state 'pending' followed by a separate mark dead
     * commits a claimable row first. The relay runs in its own coroutine, so it can claim that
     * row and forward a payload that the transform rejected. The single transaction removes the
     * window: the row never exists in state 'pending'.
     *
     * @return InboxResult.Stored on the first delivery, InboxResult.Duplicate when the pair
     *     (source, idempotencyKey) already exists, InboxResult.Error on a database failure
     */
    suspend fun storeDead(message: InboxMessage): InboxResult

    /**
     * Claims a batch of pending messages for processing.
     * Atomically selects and marks messages as processing.
     */
    suspend fun claimPending(batchSize: Int): List<InboxMessage>

    /**
     * Marks a message as processed, if the caller still owns the claim.
     *
     * Seventh review gate: the write matches the row, the state 'processing' and the claim
     * token. The reclaim step returns a row to state 'pending' on a timer, not on proof that
     * the owner died, so a worker can outlive its own claim. The fence stops that worker from
     * overwriting the row of the new owner.
     *
     * @param claimedAt the claim token of the message that the caller holds. A null token
     *     matches any claim and keeps the state fence only.
     * @return true when the write landed, false when the caller lost the claim
     */
    suspend fun markProcessed(id: UUID, claimedAt: Instant?): Boolean

    /**
     * Marks a message as dead. The relay uses this state when the message cannot be forwarded,
     * for example when the source topic template renders empty. See F-002.
     *
     * Seventh review gate: the write carries the same claim fence as [markProcessed].
     * @return true when the write landed, false when the caller lost the claim
     */
    suspend fun markDead(id: UUID, claimedAt: Instant?): Boolean

    /**
     * Counts messages in a given state.
     */
    suspend fun countByState(state: String): Long

    /**
     * Returns messages that stay in state 'processing' longer than the visibility timeout
     * back to state 'pending'.
     * @return the number of reclaimed records
     */
    suspend fun reclaimStale(olderThan: Duration): Int

    /**
     * Deletes messages in the given state older than the cutoff time.
     * @param limit the maximum number of rows to delete in one statement
     * @return the number of deleted records
     */
    suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int
}
