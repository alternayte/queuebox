package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

/**
 * Covers F-033. The age policy uses a different column per table, and the README states which.
 *
 * The outbox measures age from `updated_at`, which is the moment of the last state change.
 * The inbox measures age from `created_at`, which is the moment of receipt.
 */
@Tag("integration")
class RetentionSemanticsTest : PostgresTestBase() {

    private lateinit var outboxRepository: OutboxRepository
    private lateinit var inboxRepository: InboxRepository

    @BeforeEach
    fun setup() {
        outboxRepository = OutboxRepository()
        inboxRepository = InboxRepository()
    }

    @Test
    fun `outbox age uses updated_at and not created_at`() = runBlocking {
        val now = Clock.System.now()
        val cutoff = now - 7.days

        // Old row, but it changed state recently. It must stay.
        val recentlyUpdated = insertOutboxMessage(
            state = "sent",
            createdAt = now - 30.days,
            updatedAt = now
        )

        // Recently created row that has not changed for a long time. It must go.
        val staleUpdate = insertOutboxMessage(
            state = "sent",
            createdAt = now,
            updatedAt = now - 30.days
        )

        val deleted = outboxRepository.deleteOlderThan("sent", cutoff, 100)

        assertEquals(1, deleted)
        assertEquals("sent", getOutboxMessageState(recentlyUpdated))
        assertEquals(0L, countOutboxRowsWithId(staleUpdate))
    }

    @Test
    fun `inbox age uses created_at, which is the receipt time`() = runBlocking {
        val now = Clock.System.now()
        val cutoff = now - 7.days

        val old = insertInboxMessage(
            source = "stripe",
            idempotencyKey = "evt_old",
            state = "processed",
            createdAt = now - 30.days
        )
        val fresh = insertInboxMessage(
            source = "stripe",
            idempotencyKey = "evt_fresh",
            state = "processed",
            createdAt = now
        )

        val deleted = inboxRepository.deleteOlderThan("processed", cutoff, 100)

        assertEquals(1, deleted)
        assertEquals(0L, countInboxRowsWithId(old))
        assertEquals("processed", getInboxMessageState(fresh))
    }

    private fun countOutboxRowsWithId(id: java.util.UUID): Long =
        transaction {
            OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .count()
        }

    private fun countInboxRowsWithId(id: java.util.UUID): Long =
        transaction {
            InboxTable.selectAll()
                .where { InboxTable.id eq id }
                .count()
        }
}
