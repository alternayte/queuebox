package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each state change of an inbox row moves `updated_at`, and a lease renewal does not. A
 * downstream loader reads the rows that changed after its cursor. See
 * docs/specs/updated-at-cursor.md.
 */
@Tag("integration")
class SqlServerInboxUpdatedAtTest : SqlServerTestBase() {

    private lateinit var repository: SqlServerInboxRepository

    @BeforeEach
    fun setup() {
        repository = SqlServerInboxRepository()
    }

    @Test
    fun `every state change moves updated_at and a renewal does not`() = runBlocking {
        val processed = store("evt_processed")
        assertTrue(movedSinceBackdate(processed), "store must set updated_at")

        backdate(processed)
        val claimed = repository.claimPending(10).single()
        assertTrue(movedSinceBackdate(processed), "claim must move updated_at")

        backdate(processed)
        assertTrue(repository.renewClaim(processed, claimed.claimToken, 60_000))
        assertTrue(!movedSinceBackdate(processed), "renewal must not move updated_at")

        backdate(processed)
        assertTrue(repository.markProcessed(processed, claimed.claimToken))
        assertTrue(movedSinceBackdate(processed), "processed must move updated_at")

        val dead = store("evt_dead")
        val deadClaim = repository.claimPending(10).single()
        backdate(dead)
        assertTrue(repository.markDead(dead, deadClaim.claimToken))
        assertTrue(movedSinceBackdate(dead), "dead must move updated_at")

        val stale = store("evt_stale")
        repository.claimPending(10).single()
        backdate(stale)
        execute("UPDATE inbox SET lease_expires_at = '2000-01-01' WHERE id = '$stale'")
        assertEquals(1, repository.reclaimStale(kotlin.time.Duration.ZERO))
        assertTrue(movedSinceBackdate(stale), "reclaim must move updated_at")

        val storedDead = UUID.randomUUID()
        repository.storeDead(
            InboxMessage(
                id = storedDead,
                source = "src",
                idempotencyKey = "evt_dead_store",
                payload = JsonObject(emptyMap())
            )
        )
        assertTrue(movedSinceBackdate(storedDead), "store dead must set updated_at")
    }

    private suspend fun store(key: String): UUID {
        val id = UUID.randomUUID()
        repository.store(
            InboxMessage(id = id, source = "src", idempotencyKey = key, payload = JsonObject(emptyMap()))
        )
        return id
    }

    private fun backdate(id: UUID) = execute("UPDATE inbox SET updated_at = '2000-01-01' WHERE id = '$id'")

    private fun movedSinceBackdate(id: UUID): Boolean = transaction {
        TransactionManager.current().exec(
            "SELECT CASE WHEN updated_at > '2000-01-01' THEN 1 ELSE 0 END FROM inbox WHERE id = '$id'"
        ) { rs -> rs.next() && rs.getInt(1) == 1 } ?: false
    }

    private fun execute(sql: String) {
        transaction { TransactionManager.current().exec(sql) }
    }
}
