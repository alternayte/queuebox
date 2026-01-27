package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class InboxRepositoryTest : PostgresTestBase() {

    private lateinit var repository: InboxRepository

    @BeforeEach
    fun setup() {
        repository = InboxRepository()
    }

    // --- Storage and Deduplication tests ---

    @Test
    fun `store should return Stored when first message for key`() = runBlocking {
        val message = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )

        val result = repository.store(message)

        assertEquals(InboxResult.Stored, result)
    }

    @Test
    fun `store should return Duplicate when same source and key`() = runBlocking {
        val msg1 = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )
        val msg2 = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(mapOf("extra" to JsonPrimitive("data")))
        )

        repository.store(msg1)
        val result = repository.store(msg2)

        assertEquals(InboxResult.Duplicate, result)
    }

    @Test
    fun `store should return Stored when different sources same key`() = runBlocking {
        val msg1 = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )
        val msg2 = InboxMessage(
            source = "plaid",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )

        assertEquals(InboxResult.Stored, repository.store(msg1))
        assertEquals(InboxResult.Stored, repository.store(msg2))
    }

    @Test
    fun `store should return Stored when same source different keys`() = runBlocking {
        val msg1 = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )
        val msg2 = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_456",
            payload = JsonObject(emptyMap())
        )

        assertEquals(InboxResult.Stored, repository.store(msg1))
        assertEquals(InboxResult.Stored, repository.store(msg2))
    }

    @Test
    fun `store should set initial state to pending`() = runBlocking {
        val message = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )

        repository.store(message)

        val state = getInboxMessageState(message.id)
        assertEquals("pending", state)
    }

    // --- Claiming tests ---

    @Test
    fun `claimPending should return pending messages`() = runBlocking {
        insertInboxMessage("stripe", "evt_1")
        insertInboxMessage("stripe", "evt_2")
        insertInboxMessage("stripe", "evt_3")

        val result = repository.claimPending(10)

        assertEquals(3, result.size)
    }

    @Test
    fun `claimPending should update state to processing`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123")

        repository.claimPending(10)

        val state = getInboxMessageState(id)
        assertEquals("processing", state)
    }

    @Test
    fun `claimPending should return empty list when no pending messages`() = runBlocking {
        insertInboxMessage("stripe", "evt_1", state = "processed")
        insertInboxMessage("stripe", "evt_2", state = "processing")

        val result = repository.claimPending(10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `claimPending should respect batch size`() = runBlocking {
        repeat(10) { i -> insertInboxMessage("stripe", "evt_$i") }

        val result = repository.claimPending(3)

        assertEquals(3, result.size)
    }

    // --- markProcessed tests ---

    @Test
    fun `markProcessed should set state to processed`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123", state = "processing")

        repository.markProcessed(id)

        val state = getInboxMessageState(id)
        assertEquals("processed", state)
    }

    @Test
    fun `markProcessed should set processedAt timestamp`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123", state = "processing")
        val before = Clock.System.now()

        repository.markProcessed(id)

        val processedAt = getInboxProcessedAt(id)
        assertNotNull(processedAt)
        assertTrue(processedAt >= before - 1.seconds)
        assertTrue(processedAt <= Clock.System.now() + 1.seconds)
    }

    @Test
    fun `markProcessed message should not be claimed again`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123", state = "processing")
        repository.markProcessed(id)

        val claimed = repository.claimPending(10)

        assertTrue(claimed.none { it.id == id })
    }
}
