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

    suspend fun reclaimStale(olderThan: Duration): Int

    suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int

    suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int
}
