package org.nxtspec

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SqlServerInboxRepositoryTest : SqlServerTestBase() {

    private val repository = SqlServerInboxRepository()

    @Test
    fun `store returns Stored for new message`() = runTest {
        val message = InboxMessage(
            source = "order-service",
            idempotencyKey = "order-123",
            eventType = "OrderCreated",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("123")))
        )

        val result = repository.store(message)

        assertEquals(InboxResult.Stored, result)
    }

    @Test
    fun `storeDead writes the row directly in state dead`() = runTest {
        val message = InboxMessage(
            source = "order-service",
            idempotencyKey = "dead-1",
            eventType = "OrderCreated",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("123")))
        )

        assertEquals(InboxResult.Stored, repository.storeDead(message))
        assertEquals(0, repository.claimPending(10).size, "A dead row must never be claimable.")
        assertEquals(1L, repository.countByState("dead"))
        assertEquals(0L, repository.countByState("pending"))
    }

    @Test
    fun `storeDead returns Duplicate for an existing key`() = runTest {
        val message = InboxMessage(
            source = "order-service",
            idempotencyKey = "dead-2",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("123")))
        )

        repository.store(message)
        val result = repository.storeDead(
            InboxMessage(
                source = "order-service",
                idempotencyKey = "dead-2",
                payload = JsonObject(mapOf("orderId" to JsonPrimitive("456")))
            )
        )

        assertEquals(InboxResult.Duplicate, result)
    }

    @Test
    fun `store returns Duplicate for same source and idempotencyKey`() = runTest {
        val message1 = InboxMessage(
            source = "order-service",
            idempotencyKey = "order-123",
            eventType = "OrderCreated",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("123")))
        )
        val message2 = InboxMessage(
            source = "order-service",
            idempotencyKey = "order-123",
            eventType = "OrderCreated",
            payload = JsonObject(mapOf("orderId" to JsonPrimitive("456")))
        )

        val result1 = repository.store(message1)
        val result2 = repository.store(message2)

        assertEquals(InboxResult.Stored, result1)
        assertEquals(InboxResult.Duplicate, result2)
    }

    @Test
    fun `store allows same idempotencyKey from different sources`() = runTest {
        val message1 = InboxMessage(
            source = "service-a",
            idempotencyKey = "key-123",
            payload = JsonObject(emptyMap())
        )
        val message2 = InboxMessage(
            source = "service-b",
            idempotencyKey = "key-123",
            payload = JsonObject(emptyMap())
        )

        val result1 = repository.store(message1)
        val result2 = repository.store(message2)

        assertEquals(InboxResult.Stored, result1)
        assertEquals(InboxResult.Stored, result2)
    }

    @RepeatedTest(3)
    fun `store with concurrent inserts handles MERGE correctly`() = runTest {
        // Given: same message being stored concurrently
        val source = "concurrent-service"
        val key = "concurrent-key-${UUID.randomUUID()}"

        val results = Collections.synchronizedList(mutableListOf<InboxResult>())

        // When: 5 coroutines try to store the same message
        runBlocking {
            (1..5).map {
                async {
                    val message = InboxMessage(
                        source = source,
                        idempotencyKey = key,
                        payload = JsonObject(mapOf("attempt" to JsonPrimitive(it)))
                    )
                    val result = repository.store(message)
                    results.add(result)
                    result
                }
            }.awaitAll()
        }

        // Then: exactly one should be Stored, rest should be Duplicate
        val storedCount = results.count { it == InboxResult.Stored }
        val duplicateCount = results.count { it == InboxResult.Duplicate }

        assertEquals(1, storedCount, "Exactly one should be stored")
        assertEquals(4, duplicateCount, "Rest should be duplicates")
    }

    @Test
    fun `claimPending returns pending messages and updates to processing`() = runTest {
        // Given: messages in various states
        val id1 = insertInboxMessage(source = "svc-1", idempotencyKey = "key-1", state = "pending")
        val id2 = insertInboxMessage(source = "svc-2", idempotencyKey = "key-2", state = "pending")
        val id3 = insertInboxMessage(source = "svc-3", idempotencyKey = "key-3", state = "processed")

        // When: claiming pending
        val claimed = repository.claimPending(10)

        // Then: only pending messages are claimed
        assertEquals(2, claimed.size)
        assertTrue(claimed.any { it.id == id1 })
        assertTrue(claimed.any { it.id == id2 })

        assertEquals("processing", getInboxMessageState(id1))
        assertEquals("processing", getInboxMessageState(id2))
        assertEquals("processed", getInboxMessageState(id3))
    }

    @Test
    fun `claimPending respects batchSize`() = runTest {
        repeat(5) { insertInboxMessage(source = "svc", idempotencyKey = "key-$it") }

        val claimed = repository.claimPending(3)

        assertEquals(3, claimed.size)
    }

    @RepeatedTest(3)
    fun `claimPending with concurrent transactions prevents duplicate claims`() = runTest {
        // Given: 15 pending messages
        repeat(15) { insertInboxMessage(source = "svc", idempotencyKey = "key-$it") }

        // When: 3 coroutines concurrently claim batches of 5
        val claimedSets = Collections.synchronizedList(mutableListOf<List<InboxMessage>>())

        runBlocking {
            (1..3).map {
                async {
                    val claimed = repository.claimPending(5)
                    claimedSets.add(claimed)
                    claimed
                }
            }.awaitAll()
        }

        // Then: all claimed message IDs should be unique
        val allClaimedIds = claimedSets.flatten().map { it.id }
        assertEquals(allClaimedIds.size, allClaimedIds.toSet().size, "Should have no duplicate claims")
        assertEquals(15, allClaimedIds.size, "All messages should be claimed")
    }

    @Test
    fun `markProcessed updates state and sets processedAt`() = runTest {
        val id = insertInboxMessage(source = "svc", idempotencyKey = "key", state = "processing")
        assertNull(getInboxProcessedAt(id))

        repository.markProcessed(id)

        assertEquals("processed", getInboxMessageState(id))
        assertNotNull(getInboxProcessedAt(id))
    }

    @Test
    fun `countByState returns correct count`() = runTest {
        insertInboxMessage(source = "s1", idempotencyKey = "k1", state = "pending")
        insertInboxMessage(source = "s2", idempotencyKey = "k2", state = "pending")
        insertInboxMessage(source = "s3", idempotencyKey = "k3", state = "processed")

        assertEquals(2, repository.countByState("pending"))
        assertEquals(1, repository.countByState("processed"))
        assertEquals(0, repository.countByState("processing"))
    }

    @Test
    fun `deleteOlderThan removes old messages by state`() = runTest {
        // Insert messages (createdAt is set automatically to now)
        insertInboxMessage(source = "s1", idempotencyKey = "k1", state = "processed")
        insertInboxMessage(source = "s2", idempotencyKey = "k2", state = "processed")
        insertInboxMessage(source = "s3", idempotencyKey = "k3", state = "pending")

        // Since we can't easily insert with old timestamps, test that future cutoff deletes all processed
        val futureCutoff = Clock.System.now() + 1.hours
        val deleted = repository.deleteOlderThan("processed", futureCutoff, 1000)

        assertEquals(2, deleted)
        assertEquals(0, repository.countByState("processed"))
        assertEquals(1, repository.countByState("pending"))
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
        repository.markProcessed(id1)

        // Now second message should be claimable
        val batch2 = repository.claimPending(10)
        assertEquals(1, batch2.size)
        assertEquals("key2", batch2[0].idempotencyKey)
    }

    @Test
    fun `store should persist aggregateId correctly`() = runTest {
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
    fun `store should allow null aggregateId`() = runTest {
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
