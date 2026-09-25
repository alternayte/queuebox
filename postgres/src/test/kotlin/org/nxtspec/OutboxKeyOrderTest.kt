package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Pins the key order of the outbox claim. See docs/specs/outbox-key-order.md.
 */
@Tag("integration")
class OutboxKeyOrderTest : PostgresTestBase() {

    private lateinit var repository: OutboxRepository

    @BeforeEach
    fun setup() {
        repository = OutboxRepository()
    }

    @Test
    fun `the claim takes only the head row of each key`() = runBlocking {
        val a = insertKeyedOutboxMessage("k1")
        insertKeyedOutboxMessage("k1")
        val c = insertKeyedOutboxMessage("k2")
        val d = insertKeyedOutboxMessage(null)
        val e = insertKeyedOutboxMessage(null)

        val claimed = repository.claimBatch(10).map { it.id }.toSet()

        assertEquals(setOf(a, c, d, e), claimed)
    }

    @Test
    fun `the sequence decides the head, not created_at`() = runBlocking {
        val now = Clock.System.now()
        val first = insertKeyedOutboxMessage("k", createdAt = now)
        insertKeyedOutboxMessage("k", createdAt = now - 1.hours)

        assertEquals(listOf(first), repository.claimBatch(10).map { it.id })
    }

    @Test
    fun `a row in flight holds back its key until it is sent`() = runBlocking {
        val first = insertKeyedOutboxMessage("k")
        val second = insertKeyedOutboxMessage("k")

        val claimed = repository.claimBatch(10).single()
        assertEquals(first, claimed.id)
        assertTrue(repository.claimBatch(10).isEmpty())

        repository.markSent(claimed.id, claimed.claimToken)
        assertEquals(listOf(second), repository.claimBatch(10).map { it.id })
    }

    @Test
    fun `a row in retry backoff holds back its key`() = runBlocking {
        insertKeyedOutboxMessage("k")
        insertKeyedOutboxMessage("k")

        val claimed = repository.claimBatch(10).single()
        repository.scheduleRetry(claimed.id, 60_000, claimed.claimToken, "broker down")

        assertTrue(repository.claimBatch(10).isEmpty())
    }

    @Test
    fun `a dead row releases its key`() = runBlocking {
        insertKeyedOutboxMessage("k")
        val second = insertKeyedOutboxMessage("k")

        val claimed = repository.claimBatch(10).single()
        repository.markDead(claimed.id, claimed.claimToken, "poison")

        assertEquals(listOf(second), repository.claimBatch(10).map { it.id })
    }

    @Test
    fun `rows with an empty key are claimed together`() = runBlocking {
        repeat(3) { insertKeyedOutboxMessage("") }

        assertEquals(3, repository.claimBatch(10).size)
    }

    @Test
    fun `concurrent claimers deliver each key in insert order`() = runBlocking {
        val inserted = (1..10).associate { k ->
            "k$k" to (1..20).map { insertKeyedOutboxMessage("k$k") }
        }
        val keyOf = inserted.flatMap { (key, ids) -> ids.map { it to key } }.toMap()
        val delivered = Collections.synchronizedList(mutableListOf<UUID>())

        withContext(Dispatchers.IO) {
            repeat(4) {
                launch {
                    while (delivered.size < 200) {
                        repository.claimBatch(5).forEach { message ->
                            delivered.add(message.id)
                            repository.markSent(message.id, message.claimToken)
                        }
                    }
                }
            }
        }

        val byKey = delivered.groupBy { keyOf.getValue(it) }
        inserted.forEach { (key, ids) -> assertEquals(ids, byKey[key], "Key $key arrived out of order") }
    }
}
