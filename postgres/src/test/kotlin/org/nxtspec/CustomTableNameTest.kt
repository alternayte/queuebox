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
import kotlin.test.assertTrue

/**
 * Covers F-011. A legitimate custom table name still works end to end.
 */
@Tag("integration")
class CustomTableNameTest : PostgresTestBase() {

    private val outboxTable = DynamicOutboxTable(OutboxColumnMapping(), "my_schema_outbox")
    private val inboxTable = DynamicInboxTable(InboxColumnMapping(), "my_schema_inbox")

    @BeforeAll
    fun createCustomTables() {
        transaction { SchemaUtils.create(outboxTable, inboxTable) }
    }

    @AfterAll
    fun dropCustomTables() {
        transaction { SchemaUtils.drop(outboxTable, inboxTable) }
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

        repository.markSent(claimed.single().id)
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

        repository.markProcessed(claimed.single().id)
        assertEquals(1L, repository.countByState("processed"))
    }
}
