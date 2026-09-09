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
    suspend fun store(message: InboxMessage): InboxResult

    suspend fun storeDead(message: InboxMessage): InboxResult

    suspend fun claimPending(batchSize: Int, leaseMs: Long = 300000): List<InboxMessage>

    suspend fun markProcessed(id: UUID, claimToken: UUID?): Boolean

    suspend fun markDead(id: UUID, claimToken: UUID?): Boolean

    /** Returns false for an expired, missing, or superseded token. */
    suspend fun renewClaim(id: UUID, claimToken: UUID?, leaseMs: Long): Boolean

    suspend fun countByState(state: String): Long

    /**
     * The age in seconds of the oldest row in state 'pending', or 0.0 when no row is pending.
     *
     * F-095. A count of pending rows cannot tell an operator whether the relay is busy or dead.
     * The age can. The poll cycle calls this and caches the result, so a metrics scrape costs no
     * query.
     */
    suspend fun oldestPendingAgeSeconds(): Double

    suspend fun reclaimStale(olderThan: Duration): Int

    suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int
}
