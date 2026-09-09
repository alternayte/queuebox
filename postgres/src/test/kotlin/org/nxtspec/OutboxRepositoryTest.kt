package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.nxtspec.repository.ReplayFilter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class OutboxRepositoryTest : PostgresTestBase() {

    private lateinit var repository: OutboxRepository

    @BeforeEach
    fun setup() {
        repository = OutboxRepository()
    }

    // --- Core CRUD Operations ---

    @Test
    fun `claimBatch should return empty list when no messages`() = runBlocking {
        val result = repository.claimBatch(10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `claimBatch should return pending messages when available`() = runBlocking {
        insertOutboxMessage("pending")
        insertOutboxMessage("pending")
        insertOutboxMessage("pending")

        val result = repository.claimBatch(10)

        assertEquals(3, result.size)
        // The claim is one statement, so the returned rows already carry the new state.
        result.forEach { assertEquals(MessageState.Processing, it.state) }
    }

    @Test
    fun `claimBatch should update state to processing after claim`() = runBlocking {
        val id = insertOutboxMessage("pending")

        repository.claimBatch(10)

        val state = getOutboxMessageState(id)
        assertEquals("processing", state)
    }

    @Test
    fun `claimBatch should respect batch size when more messages exist`() = runBlocking {
        repeat(10) { insertOutboxMessage("pending") }

        val result = repository.claimBatch(3)

        assertEquals(3, result.size)
    }

    @Test
    fun `claimBatch should not return messages scheduled for future`() = runBlocking {
        val futureTime = Clock.System.now() + 1.seconds
        insertOutboxMessage("pending", scheduledAt = futureTime)

        val result = repository.claimBatch(10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `claimBatch should return messages scheduled for past`() = runBlocking {
        val pastTime = Clock.System.now() - 1.seconds
        insertOutboxMessage("pending", scheduledAt = pastTime)

        val result = repository.claimBatch(10)

        assertEquals(1, result.size)
    }

    @Test
    fun `markSent should update state to sent`() = runBlocking {
        val id = insertOutboxMessage("processing")

        repository.markSent(id, getOutboxClaimToken(id))

        val state = getOutboxMessageState(id)
        assertEquals("sent", state)
    }

    // --- Oldest pending age (F-094) ---

    private fun insertOutboxRowCreatedSecondsAgo(seconds: Long, state: String = "pending"): UUID =
        insertOutboxMessage(state, createdAt = Clock.System.now() - seconds.seconds)

    @Test
    fun `the oldest pending age is zero on an empty table`() = runBlocking {
        assertEquals(0.0, repository.oldestPendingAgeSeconds())
    }

    @Test
    fun `the oldest pending age measures the oldest pending row`() = runBlocking {
        insertOutboxRowCreatedSecondsAgo(seconds = 60)
        insertOutboxRowCreatedSecondsAgo(seconds = 10)

        val age = repository.oldestPendingAgeSeconds()

        // The oldest row is 60 seconds old. The tolerance absorbs the round trip, and it must
        // stay wide enough that a loaded machine does not fail the test. The previous phase
        // fixed several tests that asserted a wall clock too tightly.
        assertTrue(age in 55.0..75.0, "the age was $age")
    }

    @Test
    fun `a row that is not pending does not count`() = runBlocking {
        val id = insertOutboxRowCreatedSecondsAgo(seconds = 300, state = "processing")

        repository.markSent(id, getOutboxClaimToken(id))

        assertEquals(0.0, repository.oldestPendingAgeSeconds())
    }

    @Test
    fun `scheduleRetry should persist the last error`() = runBlocking {
        val id = insertOutboxMessage("processing")

        repository.scheduleRetry(id, 1000, getOutboxClaimToken(id), "HTTP 500 from destination")

        assertEquals("HTTP 500 from destination", getOutboxLastError(id))
    }

    @Test
    fun `markDead should persist the last error`() = runBlocking {
        val id = insertOutboxMessage("processing")

        repository.markDead(id, getOutboxClaimToken(id), "No route matches topic")

        assertEquals("dead", getOutboxMessageState(id))
        assertEquals("No route matches topic", getOutboxLastError(id))
    }

    @Test
    fun `attempt should increase by exactly one per failed delivery`() = runBlocking {
        // F-017: scheduleRetry is the only method that increments the attempt count.
        // Seventh review gate: a terminal write needs a claim, so each round claims the row
        // again, like a poll cycle does.
        val id = insertOutboxMessage("pending")

        repeat(5) { index ->
            val claimed = repository.claimBatch(10).single { it.id == id }
            repository.scheduleRetry(id, 0, claimed.claimToken, "failure ${index + 1}")
            assertEquals(index + 1, getOutboxMessageStateAndAttempt(id).second)
        }
    }

    @Test
    fun `scheduleRetry should reset state to pending`() = runBlocking {
        val id = insertOutboxMessage("processing")

        repository.scheduleRetry(id, 1000, getOutboxClaimToken(id), null)

        val state = getOutboxMessageState(id)
        assertEquals("pending", state)
    }

    @Test
    fun `scheduleRetry should increment attempt`() = runBlocking {
        val id = insertOutboxMessage("processing", attempt = 1)

        repository.scheduleRetry(id, 1000, getOutboxClaimToken(id), null)

        val (_, attempt) = getOutboxMessageStateAndAttempt(id)
        assertEquals(2, attempt)
    }

    @Test
    fun `scheduleRetry message should not be claimable until scheduled time`() = runBlocking {
        val id = insertOutboxMessage("processing")
        repository.scheduleRetry(id, 10000, getOutboxClaimToken(id), null)

        val claimed = repository.claimBatch(10)

        assertTrue(claimed.none { it.id == id }, "Message scheduled for future should not be claimed")
    }

    @Test
    fun `scheduleRetry message should be claimable after scheduled time`() = runBlocking {
        val id = insertOutboxMessage("processing")
        repository.scheduleRetry(id, -1000, getOutboxClaimToken(id), null) // Schedule in the past

        val claimed = repository.claimBatch(10)

        assertTrue(claimed.any { it.id == id }, "Message scheduled for past should be claimed")
    }

    @Test
    fun `markDead should set state to dead`() = runBlocking {
        val id = insertOutboxMessage("processing")

        repository.markDead(id, getOutboxClaimToken(id), null)

        val state = getOutboxMessageState(id)
        assertEquals("dead", state)
    }

    @Test
    fun `markDead message should not be claimable`() = runBlocking {
        val id = insertOutboxMessage("processing")
        repository.markDead(id, getOutboxClaimToken(id), null)

        val claimed = repository.claimBatch(10)

        assertTrue(claimed.none { it.id == id }, "Dead message should not be claimed")
    }

    // --- Concurrent claiming tests (FOR UPDATE SKIP LOCKED) ---

    @RepeatedTest(5)
    fun `concurrent claimBatch should not return duplicates when parallel claims`() = runBlocking {
        repeat(10) { insertOutboxMessage("pending") }

        val results = coroutineScope {
            (1..5).map {
                async(Dispatchers.IO) { repository.claimBatch(3) }
            }.awaitAll()
        }

        val allIds = results.flatten().map { it.id }
        assertEquals(allIds.size, allIds.distinct().size, "No duplicates should be claimed")
    }

    @RepeatedTest(5)
    fun `concurrent claimBatch should eventually claim all messages`() = runBlocking {
        repeat(20) { insertOutboxMessage("pending") }

        val results = coroutineScope {
            (1..4).map {
                async(Dispatchers.IO) { repository.claimBatch(5) }
            }.awaitAll()
        }

        val allIds = results.flatten().map { it.id }
        assertEquals(20, allIds.size, "All 20 messages should be claimed")
        assertEquals(allIds.size, allIds.distinct().size, "No duplicates should exist")
    }

    @Test
    fun `concurrent claimBatch with multiple repository instances should not return duplicates`() = runBlocking {
        repeat(15) { insertOutboxMessage("pending") }

        val results = coroutineScope {
            (1..5).map {
                async(Dispatchers.IO) {
                    val repo = OutboxRepository()
                    repo.claimBatch(5)
                }
            }.awaitAll()
        }

        val allIds = results.flatten().map { it.id }
        assertEquals(allIds.size, allIds.distinct().size, "No duplicates across different repository instances")
        assertEquals(15, allIds.size, "All messages should be claimed exactly once")
    }

    // --- Headers tests ---

    @Test
    fun `claimBatch should return messages with headers when present`() = runBlocking {
        val headers = buildJsonObject {
            put("X-Custom", JsonPrimitive("value"))
            put("X-Request-Id", JsonPrimitive("123"))
        }
        insertOutboxMessage("pending", headers = headers)

        val result = repository.claimBatch(10)

        assertEquals(1, result.size)
        assertEquals(mapOf("X-Custom" to "value", "X-Request-Id" to "123"), result[0].headers)
    }

    @Test
    fun `claimBatch should return empty headers map when not set`() = runBlocking {
        insertOutboxMessage("pending")

        val result = repository.claimBatch(10)

        assertEquals(1, result.size)
        assertEquals(emptyMap<String, String>(), result[0].headers)
    }

    @Test
    fun `claimBatch should handle special characters in headers`() = runBlocking {
        val headers = buildJsonObject {
            put("X-Unicode", JsonPrimitive("héllo wörld 你好"))
            put("X-Special", JsonPrimitive("value/with=special&chars"))
        }
        insertOutboxMessage("pending", headers = headers)

        val result = repository.claimBatch(10)

        assertEquals(1, result.size)
        assertEquals("héllo wörld 你好", result[0].headers["X-Unicode"])
        assertEquals("value/with=special&chars", result[0].headers["X-Special"])
    }

    // --- Aggregate type tests (F-090) ---

    @Test
    fun `an aggregate type round trips through the outbox row`() = runBlocking {
        val message = OutboxMessage(
            topic = "orders.created",
            payload = JsonObject(emptyMap()),
            aggregateType = "Task"
        )
        repository.insert(message)

        val claimed = repository.claimBatch(10)

        assertEquals("Task", claimed.single { it.id == message.id }.aggregateType)
    }

    @Test
    fun `a row with no aggregate type still publishes`() = runBlocking {
        // The column is nullable, so an existing writer that never sets it must not break.
        val message = OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()))
        repository.insert(message)

        val claimed = repository.claimBatch(10)

        assertTrue(claimed.any { it.id == message.id })
        assertNull(claimed.first { it.id == message.id }.aggregateType)
    }

    // --- Replay (F-096) ---

    @Test
    fun `replay moves a sent row back to pending and resets the attempt`() = runBlocking {
        val id = insertOutboxMessage(state = "sent", attempt = 3)

        val moved = repository.replay(ReplayFilter(ids = listOf(id)))

        assertEquals(1L, moved)
        val (state, attempt) = getOutboxMessageStateAndAttempt(id)
        assertEquals("pending", state)
        assertEquals(0, attempt)
        assertNull(getOutboxLastError(id))
    }

    @Test
    fun `replay leaves a processing row alone`() = runBlocking {
        val id = insertOutboxMessage(state = "processing")

        val moved = repository.replay(ReplayFilter(ids = listOf(id)))

        // The relay owns a row in state 'processing'. Replay must never take it.
        assertEquals(0L, moved)
        assertEquals("processing", getOutboxMessageState(id))
    }

    @Test
    fun `replay selects a time range`() = runBlocking {
        val old = insertOutboxMessage(state = "sent", createdAt = Clock.System.now() - 3600.seconds)
        val recent = insertOutboxMessage(state = "sent", createdAt = Clock.System.now() - 10.seconds)

        val moved = repository.replay(ReplayFilter(createdAfter = Clock.System.now() - 60.seconds))

        assertEquals(1L, moved)
        assertEquals("sent", getOutboxMessageState(old))
        assertEquals("pending", getOutboxMessageState(recent))
    }

    @Test
    fun `replay never moves a row that fails the state filter even with a matching id`() = runBlocking {
        val pendingId = insertOutboxMessage(state = "pending")

        // state IN ('sent', 'dead') must gate every replay, so an id filter alone cannot move
        // a pending row that the relay still owns.
        val moved = repository.replay(ReplayFilter(ids = listOf(pendingId)))

        assertEquals(0L, moved)
        assertEquals("pending", getOutboxMessageState(pendingId))
    }
}
