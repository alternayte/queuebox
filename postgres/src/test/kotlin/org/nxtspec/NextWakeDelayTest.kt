package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The delivery loop waits on `nextWakeDelayMs` whenever capture is on. A scheduled retry has no
 * capture event of its own, so this query is the only thing that wakes delivery for it. When it
 * returns the ceiling instead of the deadline, a retry waits for the reconciliation interval.
 */
@Tag("integration")
class NextWakeDelayTest : PostgresTestBase() {

    @Test
    fun `a scheduled retry gives a delay far below the ceiling`() = runBlocking {
        val repository = OutboxRepository()
        val message = OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap()))
        repository.insert(message)

        val claimed = repository.claimBatch(1).single()
        assertTrue(repository.scheduleRetry(claimed.id, 1000, claimed.claimToken, "HTTP 500"))

        val delay = repository.nextWakeDelayMs(300000)
        assertTrue(
            delay in 1..5000,
            "the retry is due in about a second, so the wait must be about a second, not $delay"
        )
    }

    @Test
    fun `an empty table gives the ceiling`() = runBlocking {
        val repository = OutboxRepository()
        assertTrue(repository.nextWakeDelayMs(300000) == 300000L)
    }
}
