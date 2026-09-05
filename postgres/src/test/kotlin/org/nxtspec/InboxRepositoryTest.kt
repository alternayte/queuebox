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
    fun `storeDead should write the row directly in state dead`() = runBlocking {
        val message = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_dead_1",
            payload = JsonObject(mapOf("secretField" to JsonPrimitive("rejected-payload")))
        )

        assertEquals(InboxResult.Stored, repository.storeDead(message))
        assertEquals(0, repository.claimPending(10).size, "A dead row must never be claimable.")
        assertEquals(1L, repository.countByState("dead"))
        assertEquals(0L, repository.countByState("pending"))
    }

    @Test
    fun `storeDead should return Duplicate when the key already exists`() = runBlocking {
        val first = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_dead_2",
            payload = JsonObject(emptyMap())
        )
        repository.store(first)

        val result = repository.storeDead(
            InboxMessage(
                source = "stripe",
                idempotencyKey = "evt_dead_2",
                payload = JsonObject(emptyMap())
            )
        )

        assertEquals(InboxResult.Duplicate, result)
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

        repository.markProcessed(id, null)

        val state = getInboxMessageState(id)
        assertEquals("processed", state)
    }

    @Test
    fun `markProcessed should set processedAt timestamp`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123", state = "processing")
        val before = Clock.System.now()

        repository.markProcessed(id, null)

        val processedAt = getInboxProcessedAt(id)
        assertNotNull(processedAt)
        assertTrue(processedAt >= before - 1.seconds)
        assertTrue(processedAt <= Clock.System.now() + 1.seconds)
    }

    @Test
    fun `markProcessed message should not be claimed again`() = runBlocking {
        val id = insertInboxMessage("stripe", "evt_123", state = "processing")
        repository.markProcessed(id, null)

        val claimed = repository.claimPending(10)

        assertTrue(claimed.none { it.id == id })
    }

    // --- Aggregate ordering tests ---

    @Test
    fun `claimPending should claim only oldest pending message per aggregate`() = runBlocking {
        // Insert 3 messages for the same aggregate
        insertInboxMessage("source", "key1", aggregateId = "order-123")
        Thread.sleep(10) // Ensure different timestamps
        insertInboxMessage("source", "key2", aggregateId = "order-123")
        Thread.sleep(10)
        insertInboxMessage("source", "key3", aggregateId = "order-123")

        // Should only claim the first (oldest) message
        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size)
        assertEquals("key1", claimed[0].idempotencyKey)
    }

    @Test
    fun `claimPending should not claim messages for aggregates with processing messages`() = runBlocking {
        // Insert one processing and one pending for same aggregate
        insertInboxMessage("source", "key1", state = "processing", aggregateId = "order-123")
        insertInboxMessage("source", "key2", state = "pending", aggregateId = "order-123")

        val claimed = repository.claimPending(10)

        // Should claim nothing since aggregate is locked
        assertTrue(claimed.none { it.aggregateId == "order-123" })
    }

    @Test
    fun `claimPending should allow parallel processing across different aggregates`() = runBlocking {
        // Insert messages for 3 different aggregates
        insertInboxMessage("source", "key1", aggregateId = "order-1")
        insertInboxMessage("source", "key2", aggregateId = "order-2")
        insertInboxMessage("source", "key3", aggregateId = "order-3")

        val claimed = repository.claimPending(10)

        // Should claim all 3 (one per aggregate)
        assertEquals(3, claimed.size)
    }

    @Test
    fun `claimPending should treat null aggregateId messages as independent`() = runBlocking {
        // Insert messages without aggregateId
        insertInboxMessage("source", "key1")
        insertInboxMessage("source", "key2")
        insertInboxMessage("source", "key3")

        val claimed = repository.claimPending(10)

        // All should be claimable (backward compatible)
        assertEquals(3, claimed.size)
    }

    @Test
    fun `claimPending should process next message after aggregate is unblocked`() = runBlocking {
        // Insert 2 messages for the same aggregate
        val id1 = insertInboxMessage("source", "key1", aggregateId = "order-123")
        Thread.sleep(10)
        insertInboxMessage("source", "key2", aggregateId = "order-123")

        // Claim first message
        val batch1 = repository.claimPending(10)
        assertEquals(1, batch1.size)
        assertEquals("key1", batch1[0].idempotencyKey)

        // Mark first as processed
        repository.markProcessed(id1, null)

        // Now second message should be claimable
        val batch2 = repository.claimPending(10)
        assertEquals(1, batch2.size)
        assertEquals("key2", batch2[0].idempotencyKey)
    }

    @Test
    fun `claimPending should mix aggregate and independent messages correctly`() = runBlocking {
        // Insert aggregate messages
        insertInboxMessage("source", "agg1", aggregateId = "order-1")
        insertInboxMessage("source", "agg2", aggregateId = "order-1") // Same aggregate, won't be claimed
        insertInboxMessage("source", "agg3", aggregateId = "order-2")

        // Insert independent messages
        insertInboxMessage("source", "ind1")
        insertInboxMessage("source", "ind2")

        val claimed = repository.claimPending(10)

        // Should claim: agg1 (first of order-1), agg3 (first of order-2), ind1, ind2
        assertEquals(4, claimed.size)
        assertTrue(claimed.any { it.idempotencyKey == "agg1" })
        assertTrue(claimed.none { it.idempotencyKey == "agg2" })
        assertTrue(claimed.any { it.idempotencyKey == "agg3" })
        assertTrue(claimed.any { it.idempotencyKey == "ind1" })
        assertTrue(claimed.any { it.idempotencyKey == "ind2" })
    }

    @Test
    fun `store should persist aggregateId correctly`() = runBlocking {
        val message = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            aggregateId = "customer-456",
            payload = JsonObject(emptyMap())
        )

        repository.store(message)
        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size)
        assertEquals("customer-456", claimed[0].aggregateId)
    }

    @Test
    fun `store should allow null aggregateId`() = runBlocking {
        val message = InboxMessage(
            source = "stripe",
            idempotencyKey = "evt_123",
            payload = JsonObject(emptyMap())
        )

        repository.store(message)
        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size)
        assertEquals(null, claimed[0].aggregateId)
    }
}
