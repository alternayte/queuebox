package org.nxtspec.repository

import kotlinx.datetime.Instant
import org.nxtspec.OutboxMessage
import java.util.UUID
import kotlin.time.Duration

/**
 * Interface for outbox repository operations.
 * Enables database-agnostic implementations for multi-database support.
 */
@Suppress("TooManyFunctions")
interface OutboxRepositoryInterface {
    suspend fun claimBatch(batchSize: Int, leaseMs: Long = 300000): List<OutboxMessage>

    suspend fun insert(message: OutboxMessage)

    suspend fun markSent(id: UUID, claimToken: UUID?): Boolean

    suspend fun scheduleRetry(id: UUID, delayMs: Long, claimToken: UUID?, error: String? = null): Boolean

    suspend fun markDead(id: UUID, claimToken: UUID?, error: String? = null): Boolean

    /** Returns false for an expired, missing, or superseded token. */
    suspend fun renewClaim(id: UUID, claimToken: UUID?, leaseMs: Long): Boolean

    suspend fun nextWakeDelayMs(maxWaitMs: Long): Long = maxWaitMs

    suspend fun countByState(state: String): Long

    /**
     * The age in seconds of the oldest row in state 'pending', or 0.0 when no row is pending.
     *
     * F-094. A count of pending rows cannot tell an operator whether the relay is busy or dead.
     * The age can. The poll cycle calls this and caches the result, so a metrics scrape costs no
     * query.
     */
    suspend fun oldestPendingAgeSeconds(): Double

    suspend fun reclaimStale(olderThan: Duration): Int

    suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int

    suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int

    /**
     * Moves every row that matches [filter] and sits in state 'sent' or 'dead' back to state
     * 'pending'. The move resets `attempt` to 0 and clears `last_error`, because a dead row sits
     * at its attempt ceiling and would fail once and return to dead without the reset. See F-096.
     *
     * A row in state 'pending' or 'processing' never moves, whatever the filter says, because
     * the relay owns that row.
     *
     * @return the count of rows moved.
     */
    suspend fun replay(filter: ReplayFilter): Long

    /**
     * Every distinct topic currently present in the outbox table. F-096. A replay by destination
     * resolves this set through the real router before it queries, because only the router knows
     * the first-match precedence between overlapping route patterns.
     */
    suspend fun distinctTopics(): List<String>
}
