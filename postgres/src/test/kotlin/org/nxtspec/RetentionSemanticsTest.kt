package org.nxtspec

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Covers F-033. The README states which column the age policy uses.
 *
 * Both tables measure age from `updated_at`, which is the moment of the last state change.
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
    fun `inbox age uses updated_at, which is the last state change`() = runBlocking {
        val now = Clock.System.now()
        val cutoff = now - 7.days

        // Received long ago, finished recently after a long retry. It must stay, so a loader
        // sees its final state. See docs/specs/updated-at-cursor.md.
        val finishedLate = insertInboxMessage(
            source = "stripe",
            idempotencyKey = "evt_finished_late",
            state = "processed",
            createdAt = now - 30.days,
            updatedAt = now
        )
        val stale = insertInboxMessage(
            source = "stripe",
            idempotencyKey = "evt_stale",
            state = "processed",
            createdAt = now - 30.days,
            updatedAt = now - 30.days
        )

        val deleted = inboxRepository.deleteOlderThan("processed", cutoff, 100)

        assertEquals(1, deleted)
        assertEquals(0L, countInboxRowsWithId(stale))
        assertEquals("processed", getInboxMessageState(finishedLate))
    }

    private fun countOutboxRowsWithId(id: java.util.UUID): Long = transaction {
        OutboxTable.selectAll()
            .where { OutboxTable.id eq id }
            .count()
    }

    private fun countInboxRowsWithId(id: java.util.UUID): Long = transaction {
        InboxTable.selectAll()
            .where { InboxTable.id eq id }
            .count()
    }
}
