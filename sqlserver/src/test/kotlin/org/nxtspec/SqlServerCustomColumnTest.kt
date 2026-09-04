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

/**
 * Covers F-032. A column mapping that uses reserved words must work end to end.
 */
@Tag("integration")
class SqlServerCustomColumnTest : SqlServerTestBase() {

    private val reservedMapping = OutboxColumnMapping(topic = "user", key = "order")
    private val reservedTable = SqlServerDynamicOutboxTable(reservedMapping, "reserved_outbox")

    private val reservedInboxMapping = InboxColumnMapping(eventType = "user", aggregateId = "order")
    private val reservedInboxTable = SqlServerDynamicInboxTable(reservedInboxMapping, "reserved_inbox")

    @BeforeAll
    fun createReservedTable() {
        transaction { SchemaUtils.create(reservedTable, reservedInboxTable) }
    }

    @AfterAll
    fun dropReservedTable() {
        transaction { SchemaUtils.drop(reservedTable, reservedInboxTable) }
    }

    @Test
    fun `outbox works when the column mapping uses reserved words`() = runBlocking {
        val repository = SqlServerOutboxRepository(reservedMapping, "reserved_outbox")

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

        repository.scheduleRetry(claimed.single().id, 0, "HTTP 500")
        assertEquals(1L, repository.countByState("pending"))

        val reclaimed = repository.claimBatch(10)
        assertEquals(1, reclaimed.size)
        repository.markDead(reclaimed.single().id, "gave up")
        assertEquals(1L, repository.countByState("dead"))
    }

    @Test
    fun `inbox works when the column mapping uses reserved words`() = runBlocking {
        val repository = SqlServerInboxRepository(reservedInboxMapping, "reserved_inbox")

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

        repository.markProcessed(claimed.single().id)
        assertEquals(1L, repository.countByState("processed"))

        assertEquals(0, repository.reclaimStale(kotlin.time.Duration.ZERO))
    }
}
