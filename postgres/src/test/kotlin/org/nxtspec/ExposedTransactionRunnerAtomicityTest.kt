package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * F-002: proves that a repository call inside `inTransaction` joins the open transaction.
 *
 * A repository call that starts its own transaction commits on its own. The block of the
 * runner then loses the all or nothing property. The relay pair of the outbox insert and the
 * inbox mark must commit together.
 */
@Tag("integration")
class ExposedTransactionRunnerAtomicityTest : PostgresTestBase() {

    private lateinit var runner: ExposedTransactionRunner
    private lateinit var outboxRepository: OutboxRepository
    private lateinit var inboxRepository: InboxRepository

    @BeforeEach
    fun setup() {
        runner = ExposedTransactionRunner()
        outboxRepository = OutboxRepository()
        inboxRepository = InboxRepository()
    }

    private fun countOutboxRows(): Long = transaction {
        OutboxTable.selectAll().count()
    }

    private fun newOutboxMessage(topic: String): OutboxMessage = OutboxMessage(
        id = UUID.randomUUID(),
        topic = topic,
        payload = JsonObject(emptyMap())
    )

    @Test
    fun `a repository write inside inTransaction rolls back when the block throws`() = runBlocking {
        // Exposed runs the suspended transaction in a child coroutine. supervisorScope stops
        // the failure from cancelling the test coroutine.
        val failure = supervisorScope {
            runCatching {
                runner.inTransaction {
                    outboxRepository.insert(newOutboxMessage("atomicity-topic"))
                    error("forced failure after the insert")
                }
            }
        }

        assertEquals(true, failure.isFailure, "The block must propagate the failure.")
        assertEquals(0L, countOutboxRows(), "ROWS-SURVIVING-ROLLBACK")
    }

    @Test
    fun `the relay pair is atomic when the second call throws`() = runBlocking {
        val inboxId = insertInboxMessage(source = "relay-source", idempotencyKey = "relay-key-1")

        val failure = supervisorScope {
            runCatching {
                runner.inTransaction {
                    outboxRepository.insert(newOutboxMessage("relay-topic"))
                    inboxRepository.markProcessed(inboxId, null)
                    error("forced failure after the pair")
                }
            }
        }

        assertEquals(true, failure.isFailure, "The block must propagate the failure.")
        assertEquals(0L, countOutboxRows(), "The outbox insert must roll back.")
        assertEquals("pending", getInboxMessageState(inboxId), "The inbox mark must roll back.")
    }

    @Test
    fun `a repository call without an outer transaction still commits`() = runBlocking {
        outboxRepository.insert(newOutboxMessage("standalone-topic"))

        assertEquals(1L, countOutboxRows(), "The unchanged behaviour must commit on its own.")
    }
}
