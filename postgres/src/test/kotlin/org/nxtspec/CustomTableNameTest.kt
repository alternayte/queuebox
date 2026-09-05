package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers F-011. A legitimate custom table name still works end to end.
 */
@Tag("integration")
class CustomTableNameTest : PostgresTestBase() {

    private val outboxTable = DynamicOutboxTable(OutboxColumnMapping(), "my_schema_outbox")
    private val inboxTable = DynamicInboxTable(InboxColumnMapping(), "my_schema_inbox")

    // F-032: reserved words as column names must work, because the feature is documented as
    // supporting an existing schema.
    private val reservedMapping = OutboxColumnMapping(topic = "user", key = "order")
    private val reservedTable = DynamicOutboxTable(reservedMapping, "reserved_outbox")

    private val reservedInboxMapping = InboxColumnMapping(eventType = "user", aggregateId = "order")
    private val reservedInboxTable = DynamicInboxTable(reservedInboxMapping, "reserved_inbox")

    // The V6 columns are part of the mapped surface, so a custom schema must be able to name
    // them as freely as the older columns. Every name here is a reserved word.
    private val leaseOutboxMapping =
        OutboxColumnMapping(claimToken = "check", leaseExpiresAt = "end", claimedAt = "grant")
    private val leaseOutboxTable = DynamicOutboxTable(leaseOutboxMapping, "lease_outbox")

    private val leaseInboxMapping = InboxColumnMapping(
        claimToken = "check",
        leaseExpiresAt = "end",
        consumption = "grant",
        scheduledAt = "offset",
        attempt = "limit",
        lastError = "authorization"
    )
    private val leaseInboxTable = DynamicInboxTable(leaseInboxMapping, "lease_inbox")

    @BeforeAll
    fun createCustomTables() {
        transaction {
            SchemaUtils.create(
                outboxTable,
                inboxTable,
                reservedTable,
                reservedInboxTable,
                leaseOutboxTable,
                leaseInboxTable
            )
        }
    }

    @AfterAll
    fun dropCustomTables() {
        transaction {
            SchemaUtils.drop(
                outboxTable,
                inboxTable,
                reservedTable,
                reservedInboxTable,
                leaseOutboxTable,
                leaseInboxTable
            )
        }
    }

    @Test
    fun `outbox works with a custom table name`() = runBlocking {
        val repository = OutboxRepository(OutboxColumnMapping(), "my_schema_outbox")

        repository.insert(
            OutboxMessage(
                topic = "order.created",
                key = "order-1",
                payload = JsonObject(mapOf("amount" to JsonPrimitive(10))),
                headers = mapOf("x-source" to "test")
            )
        )

        val claimed = repository.claimBatch(10)

        assertEquals(1, claimed.size)
        assertEquals("order.created", claimed.single().topic)
        assertEquals("order-1", claimed.single().key)
        assertEquals("test", claimed.single().headers["x-source"])

        repository.markSent(claimed.single().id, claimed.single().claimToken)
        assertEquals(1L, repository.countByState("sent"))
    }

    @Test
    fun `inbox works with a custom table name`() = runBlocking {
        val repository = InboxRepository(InboxColumnMapping(), "my_schema_inbox")

        val stored = repository.store(
            InboxMessage(
                source = "stripe",
                idempotencyKey = "evt_custom_1",
                aggregateId = "cus_1",
                eventType = "payment.succeeded",
                payload = JsonObject(mapOf("amount" to JsonPrimitive(10)))
            )
        )
        assertEquals(InboxResult.Stored, stored)

        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size)
        assertEquals("stripe", claimed.single().source)
        assertTrue(claimed.single().aggregateId == "cus_1")

        repository.markProcessed(claimed.single().id, claimed.single().claimToken)
        assertEquals(1L, repository.countByState("processed"))
    }

    @Test
    fun `outbox works when the column mapping uses reserved words`() = runBlocking {
        val repository = OutboxRepository(reservedMapping, "reserved_outbox")

        repository.insert(
            OutboxMessage(
                topic = "order.created",
                key = "order-1",
                payload = JsonObject(mapOf("amount" to JsonPrimitive(10)))
            )
        )

        val claimed = repository.claimBatch(10)

        assertEquals(1, claimed.size)
        assertEquals("order.created", claimed.single().topic)
        assertEquals("order-1", claimed.single().key)

        repository.scheduleRetry(claimed.single().id, 0, claimed.single().claimToken, "HTTP 500")
        assertEquals(1L, repository.countByState("pending"))

        val reclaimed = repository.claimBatch(10)
        assertEquals(1, reclaimed.size)
        repository.markDead(reclaimed.single().id, reclaimed.single().claimToken, "gave up")
        assertEquals(1L, repository.countByState("dead"))
    }

    @Test
    fun `inbox works when the column mapping uses reserved words`() = runBlocking {
        val repository = InboxRepository(reservedInboxMapping, "reserved_inbox")

        assertEquals(
            InboxResult.Stored,
            repository.store(
                InboxMessage(
                    source = "stripe",
                    idempotencyKey = "evt_reserved_1",
                    aggregateId = "cus_1",
                    eventType = "payment.succeeded",
                    payload = JsonObject(mapOf("amount" to JsonPrimitive(10)))
                )
            )
        )

        val claimed = repository.claimPending(10)

        assertEquals(1, claimed.size)
        assertEquals("cus_1", claimed.single().aggregateId)
        assertEquals("payment.succeeded", claimed.single().eventType)

        repository.markProcessed(claimed.single().id, claimed.single().claimToken)
        assertEquals(1L, repository.countByState("processed"))

        assertEquals(0, repository.reclaimStale(kotlin.time.Duration.ZERO))
    }

    @Test
    fun `the outbox claim columns work under a custom mapping`() = runBlocking {
        val repository = OutboxRepository(leaseOutboxMapping, "lease_outbox")

        repository.insert(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        val claimed = repository.claimBatch(10, 60000).single()
        assertTrue(claimed.claimToken != null, "the mapped claim token must be written and read back")

        assertTrue(repository.renewClaim(claimed.id, claimed.claimToken, 60000))
        assertFalse(
            repository.renewClaim(claimed.id, java.util.UUID.randomUUID(), 60000),
            "a foreign token must not renew the mapped lease"
        )
        assertFalse(repository.markSent(claimed.id, java.util.UUID.randomUUID()))
        assertTrue(repository.markSent(claimed.id, claimed.claimToken))
        assertEquals(1L, repository.countByState("sent"))
    }

    @Test
    fun `the inbox claim and consumption columns work under a custom mapping`() = runBlocking {
        val repository = InboxRepository(leaseInboxMapping, "lease_inbox")

        assertEquals(
            InboxResult.Stored,
            repository.store(
                InboxMessage(
                    source = "orders",
                    idempotencyKey = "evt_mapped_push",
                    payload = JsonObject(emptyMap()),
                    consumption = "push"
                )
            )
        )
        assertEquals(
            InboxResult.Stored,
            repository.store(
                InboxMessage(
                    source = "orders",
                    idempotencyKey = "evt_mapped_pull",
                    payload = JsonObject(emptyMap()),
                    consumption = "pull"
                )
            )
        )

        // The relay claims push rows only, whatever the column is called.
        val claimed = repository.claimPending(10, 60000)
        assertEquals(1, claimed.size)
        assertEquals("evt_mapped_push", claimed.single().idempotencyKey)

        val message = claimed.single()
        assertTrue(repository.renewClaim(message.id, message.claimToken, 60000))
        assertFalse(repository.markProcessed(message.id, java.util.UUID.randomUUID()))
        assertTrue(repository.markProcessed(message.id, message.claimToken))
        assertEquals(1L, repository.countByState("processed"))
        assertEquals(1L, repository.countByState("pending"))
    }
}
