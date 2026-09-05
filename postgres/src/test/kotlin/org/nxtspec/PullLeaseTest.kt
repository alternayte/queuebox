package org.nxtspec

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration

@Tag("integration")
class PullLeaseTest : PostgresTestBase() {
    private val repository = InboxRepository()
    private val outbox = OutboxRepository()

    private fun statement(connection: Connection, action: String, values: Map<String, Any?>): PreparedStatement {
        val path = Path.of("../examples/pull/sql/postgresql/$action.sql")
        val names = mutableListOf<String>()
        val sql = Regex(":([a-z_]+)").replace(Files.readString(path)) {
            names.add(it.groupValues[1])
            "?"
        }
        return connection.prepareStatement(sql).also { stmt ->
            names.forEachIndexed { index, name ->
                val value = values.getValue(name)
                stmt.setObject(index + 1, value)
            }
        }
    }

    private suspend fun receipt(key: String = UUID.randomUUID().toString(), consumption: String = "pull"): UUID {
        val message =
            InboxMessage(
                source = "orders",
                idempotencyKey = key,
                payload = JsonObject(emptyMap()),
                consumption = consumption
            )
        assertEquals(InboxResult.Stored, repository.store(message))
        return message.id
    }

    private fun claim(batch: Int = 1, lease: Int = 5000): List<Pair<UUID, UUID>> =
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            statement(connection, "claim", mapOf("source" to "orders", "batch" to batch, "lease_ms" to lease)).use {
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                UUID.fromString(rows.getString("id")) to UUID.fromString(rows.getString("claim_token"))
                            )
                        }
                    }
                }
            }
        }

    private fun change(action: String, claim: Pair<UUID, UUID>, connection: Connection? = null): Int {
        val values = mapOf(
            "id" to claim.first,
            "token" to claim.second,
            "lease_ms" to 5000,
            "delay_ms" to 0,
            "error" to "test failure"
        )
        if (connection != null) return statement(connection, action, values).use { it.executeUpdate() }
        return dataSource.connection.use { conn ->
            conn.autoCommit = true
            statement(conn, action, values).use { it.executeUpdate() }
        }
    }

    @Test fun `persisted consumption isolates push and pull including duplicate receipts`() = runBlocking {
        val pull = receipt("same", "pull")
        val push = receipt("other", "push")
        assertEquals(
            InboxResult.Duplicate,
            repository.store(
                InboxMessage(
                    source = "orders",
                    idempotencyKey = "same",
                    payload = JsonObject(emptyMap()),
                    consumption = "push"
                )
            )
        )
        assertEquals(listOf(push), repository.claimPending(10).map { it.id })
        assertEquals(listOf(pull), claim(10).map { it.first })
    }

    @Test fun `pull claims are disjoint across concurrent workers`() = runBlocking {
        repeat(20) { receipt() }
        val groups = coroutineScope { (1..4).map { async(Dispatchers.IO) { claim(5) } }.awaitAll() }
        val first = groups.flatten()
        assertEquals(first.size, first.map { it.first }.toSet().size)
        val all = first + claim(20)
        assertEquals(20, all.size)
        assertEquals(20, all.map { it.first }.toSet().size)
    }

    @Test fun `expired token cannot renew complete retry or dead letter reclaimed work`() = runBlocking {
        receipt()
        val stale = claim(lease = 50).single()
        delay(100)
        for (action in listOf("renew", "complete", "retry", "dead")) assertEquals(0, change(action, stale))
        val current = claim().single()
        assertNotEquals(stale.second, current.second)
        for (action in listOf("renew", "complete", "retry", "dead")) assertEquals(0, change(action, stale))
        assertEquals(1, change("renew", current))
        assertEquals(1, change("retry", current))
        val retried = claim().single()
        assertEquals(1, change("dead", retried))
        assertTrue(claim().isEmpty())
    }

    @Test fun `completion and business writes commit or roll back together`() = runBlocking {
        receipt()
        val owned = claim().single()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.executeUpdate("UPDATE inbox SET aggregate_id = 'business-effect' WHERE id = '${owned.first}'")
            }
            assertEquals(0, change("complete", owned.first to UUID.randomUUID(), connection))
            connection.rollback()
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT aggregate_id FROM inbox WHERE id = '${owned.first}'").use { rows ->
                    rows.next()
                    assertNull(rows.getString(1))
                }
            }
            connection.autoCommit = false
            connection.createStatement().use {
                it.executeUpdate("UPDATE inbox SET aggregate_id = 'business-effect' WHERE id = '${owned.first}'")
            }
            assertEquals(1, change("complete", owned, connection))
            connection.commit()
        }
        assertEquals("processed", getInboxMessageState(owned.first))
        assertEquals(0, change("complete", owned))
    }

    @Test fun `outgoing renewal protects ownership and null tokens never bypass the fence`() = runBlocking {
        val id = insertOutboxMessage("pending")
        val claim = outbox.claimBatch(1, 1000).single()
        assertFalse(outbox.markSent(id, null))
        assertTrue(outbox.renewClaim(id, claim.claimToken, 5000))
        delay(1100)
        assertEquals(0, outbox.reclaimStale(Duration.ZERO))
        assertTrue(outbox.markSent(id, claim.claimToken))
        assertFalse(outbox.renewClaim(id, claim.claimToken, 5000))
    }

    @Test fun `expired outgoing ownership cannot mutate even before reclaim`() = runBlocking {
        val id = insertOutboxMessage("pending")
        val stale = outbox.claimBatch(1, 50).single()
        delay(100)
        assertFalse(outbox.renewClaim(id, stale.claimToken, 5000))
        assertFalse(outbox.markSent(id, stale.claimToken))
        assertFalse(outbox.scheduleRetry(id, 0, stale.claimToken))
        assertFalse(outbox.markDead(id, stale.claimToken))
        assertEquals(1, outbox.reclaimStale(Duration.ZERO))
        val current = outbox.claimBatch(1).single()
        assertNotEquals(current.claimToken, stale.claimToken)
        assertFalse(outbox.markSent(id, stale.claimToken))
        assertTrue(outbox.markSent(id, current.claimToken))
    }

    @Test fun `retention rejects active work`() = runBlocking {
        val now = kotlinx.datetime.Clock.System.now()
        assertFailsWith<IllegalArgumentException> { repository.deleteOlderThan("pending", now, 10) }
        assertFailsWith<IllegalArgumentException> { outbox.deleteExceptMostRecent("processing", 0, 10) }
        Unit
    }
}
