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

    @BeforeAll
    fun createReservedTable() {
        transaction { SchemaUtils.create(reservedTable) }
    }

    @AfterAll
    fun dropReservedTable() {
        transaction { SchemaUtils.drop(reservedTable) }
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
}
