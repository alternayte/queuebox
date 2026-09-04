package org.nxtspec

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SqlServerOutboxRepositoryTest : SqlServerTestBase() {

    private val repository = SqlServerOutboxRepository()

    @Test
    fun `claimBatch returns pending messages and updates to processing`() = runTest {
        // Given: multiple pending messages
        val id1 = insertOutboxMessage(state = "pending", topic = "test-1")
        val id2 = insertOutboxMessage(state = "pending", topic = "test-2")
        val id3 = insertOutboxMessage(state = "sent", topic = "test-3") // should not be claimed

        // When: claiming a batch
        val claimed = repository.claimBatch(10)

        // Then: only pending messages are claimed and updated to processing
        assertEquals(2, claimed.size)
        assertTrue(claimed.any { it.id == id1 })
        assertTrue(claimed.any { it.id == id2 })

        assertEquals("processing", getOutboxMessageState(id1))
        assertEquals("processing", getOutboxMessageState(id2))
        assertEquals("sent", getOutboxMessageState(id3))
    }

    @Test
    fun `claimBatch respects scheduledAt`() = runTest {
        // Given: one message scheduled in the past, one in the future
        val pastTime = Clock.System.now() - 1.minutes
        val futureTime = Clock.System.now() + 1.hours

        val pastId = insertOutboxMessage(state = "pending", scheduledAt = pastTime)
        val futureId = insertOutboxMessage(state = "pending", scheduledAt = futureTime)

        // When: claiming batch
        val claimed = repository.claimBatch(10)

        // Then: only past-scheduled message is claimed
        assertEquals(1, claimed.size)
        assertEquals(pastId, claimed.first().id)
        assertEquals("processing", getOutboxMessageState(pastId))
        assertEquals("pending", getOutboxMessageState(futureId))
    }

    @Test
    fun `claimBatch respects batchSize limit`() = runTest {
        // Given: more messages than batch size
        repeat(5) { insertOutboxMessage(state = "pending") }

        // When: claiming with limit
        val claimed = repository.claimBatch(3)

        // Then: only 3 are claimed
        assertEquals(3, claimed.size)
    }

    @RepeatedTest(3)
    fun `claimBatch with concurrent transactions prevents duplicate claims using READPAST`() = runTest {
        // Given: 20 pending messages
        (1..20).map { insertOutboxMessage(state = "pending") }

        // When: 5 coroutines concurrently claim batches of 5
        val claimedSets = Collections.synchronizedList(mutableListOf<List<OutboxMessage>>())

        runBlocking {
            (1..5).map {
                async {
                    val claimed = repository.claimBatch(5)
                    claimedSets.add(claimed)
                    claimed
                }
            }.awaitAll()
        }

        // Then: all claimed message IDs should be unique (no duplicates due to READPAST)
        val allClaimedIds = claimedSets.flatten().map { it.id }
        assertEquals(allClaimedIds.size, allClaimedIds.toSet().size, "Should have no duplicate claims")

        // All messages should be claimed (20 messages, 5 concurrent claims of 5)
        assertEquals(20, allClaimedIds.size, "All messages should be claimed")
    }

    @Test
    fun `markSent updates state to sent`() = runTest {
        val id = insertOutboxMessage(state = "processing")

        repository.markSent(id)

        assertEquals("sent", getOutboxMessageState(id))
    }

    @Test
    fun `scheduleRetry persists the last error and increments the attempt once`() = runTest {
        val id = insertOutboxMessage(state = "processing")

        repository.scheduleRetry(id, 1000, "HTTP 500 from destination")

        val (state, attempt) = getOutboxMessageStateAndAttempt(id)
        assertEquals("pending", state)
        assertEquals(1, attempt)
        assertEquals("HTTP 500 from destination", getOutboxLastError(id))
    }

    @Test
    fun `scheduleRetry updates scheduledAt and resets to pending`() = runTest {
        val id = insertOutboxMessage(state = "failed", attempt = 1)
        val beforeSchedule = Clock.System.now()

        repository.scheduleRetry(id, 60000, null) // 60 seconds

        val (state, scheduledAt) = getOutboxStateAndScheduledAt(id)
        assertEquals("pending", state)
        assertTrue(scheduledAt > beforeSchedule + 50.seconds, "scheduledAt should be in the future")
    }

    @Test
    fun `markDead updates state to dead`() = runTest {
        val id = insertOutboxMessage(state = "failed", attempt = 5)

        repository.markDead(id, null)

        assertEquals("dead", getOutboxMessageState(id))
    }

    @Test
    fun `countByState returns correct count`() = runTest {
        insertOutboxMessage(state = "pending")
        insertOutboxMessage(state = "pending")
        insertOutboxMessage(state = "sent")
        insertOutboxMessage(state = "dead")

        assertEquals(2, repository.countByState("pending"))
        assertEquals(1, repository.countByState("sent"))
        assertEquals(1, repository.countByState("dead"))
        assertEquals(0, repository.countByState("processing"))
    }

    @Test
    fun `deleteOlderThan removes old messages by state`() = runTest {
        // Given: messages with different updatedAt times
        val oldTime = Clock.System.now() - 2.hours
        val recentTime = Clock.System.now() - 30.minutes

        insertOutboxMessage(state = "sent", updatedAt = oldTime)
        insertOutboxMessage(state = "sent", updatedAt = recentTime)
        insertOutboxMessage(state = "pending", updatedAt = oldTime)

        // When: deleting sent messages older than 1 hour
        val cutoff = Clock.System.now() - 1.hours
        val deleted = repository.deleteOlderThan("sent", cutoff, 1000)

        // Then: only old sent message is deleted
        assertEquals(1, deleted)
        assertEquals(1, repository.countByState("sent"))
        assertEquals(1, repository.countByState("pending"))
    }

    @Test
    fun `deleteExceptMostRecent keeps only specified number`() = runTest {
        // Given: 5 sent messages
        repeat(5) { insertOutboxMessage(state = "sent") }
        insertOutboxMessage(state = "pending") // should not be affected

        // When: keeping only most recent 2
        val deleted = repository.deleteExceptMostRecent("sent", 2, 1000)

        // Then: 3 are deleted, 2 remain
        assertEquals(3, deleted)
        assertEquals(2, repository.countByState("sent"))
        assertEquals(1, repository.countByState("pending"))
    }

    // --- Headers tests ---

    @Test
    fun `claimBatch returns messages with headers when present`() = runTest {
        // Given: a message with custom headers
        val headersJson = """{"X-Custom":"value","X-Request-Id":"123"}"""
        insertOutboxMessage(state = "pending", headers = headersJson)

        // When: claiming the batch
        val claimed = repository.claimBatch(10)

        // Then: headers are correctly parsed
        assertEquals(1, claimed.size)
        assertEquals(mapOf("X-Custom" to "value", "X-Request-Id" to "123"), claimed[0].headers)
    }

    @Test
    fun `claimBatch returns empty headers when not set`() = runTest {
        // Given: a message with default empty headers
        insertOutboxMessage(state = "pending")

        // When: claiming the batch
        val claimed = repository.claimBatch(10)

        // Then: headers default to empty map
        assertEquals(1, claimed.size)
        assertEquals(emptyMap<String, String>(), claimed[0].headers)
    }

    @Test
    fun `claimBatch handles special characters in headers`() = runTest {
        // Given: headers with special characters
        val headersJson = """{"X-Unicode":"héllo wörld","X-Special":"value/with=chars"}"""
        insertOutboxMessage(state = "pending", headers = headersJson)

        // When: claiming the batch
        val claimed = repository.claimBatch(10)

        // Then: special characters are preserved
        assertEquals(1, claimed.size)
        assertEquals("héllo wörld", claimed[0].headers["X-Unicode"])
        assertEquals("value/with=chars", claimed[0].headers["X-Special"])
    }
}
