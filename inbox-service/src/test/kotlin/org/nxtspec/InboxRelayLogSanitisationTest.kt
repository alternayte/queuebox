package org.nxtspec

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.TransactionRunner
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Fourth review gate, defect 3. A failed forward must log a sanitised reason. A raw throwable
 * carries the whole cause chain into the log, and a cause can hold a credential.
 */
class InboxRelayLogSanitisationTest {

    private val logbackLogger =
        LoggerFactory.getLogger("org.nxtspec") as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>()

    private fun startCapture() {
        appender.context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.start()
        logbackLogger.addAppender(appender)
        logbackLogger.level = Level.DEBUG
    }

    @AfterTest
    fun stopCapture() {
        logbackLogger.detachAppender(appender)
        appender.stop()
    }

    private class SingleMessageInbox(private val pending: MutableList<InboxMessage>) :
        InboxRepositoryInterface {
        override suspend fun store(message: InboxMessage): InboxResult = InboxResult.Stored
        override suspend fun storeDead(message: InboxMessage): InboxResult = InboxResult.Stored
        override suspend fun claimPending(batchSize: Int): List<InboxMessage> {
            val claimed = pending.take(batchSize)
            pending.removeAll(claimed)
            return claimed
        }

        override suspend fun markProcessed(id: UUID, claimedAt: Instant?): Boolean = true
        override suspend fun markDead(id: UUID, claimedAt: Instant?): Boolean = true
        override suspend fun countByState(state: String): Long = 0
        override suspend fun reclaimStale(olderThan: Duration): Int = 0
        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = 0
    }

    private class SecretLeakingOutbox : OutboxRepositoryInterface {
        override suspend fun claimBatch(batchSize: Int): List<OutboxMessage> = emptyList()
        override suspend fun insert(message: OutboxMessage): Unit = throw IllegalStateException(
            "insert failed",
            IllegalStateException("Authorization: Bearer super-secret-token")
        )

        override suspend fun markSent(id: UUID, claimedAt: Instant?): Boolean = true
        override suspend fun scheduleRetry(id: UUID, delayMs: Long, claimedAt: Instant?, error: String?): Boolean = true
        override suspend fun markDead(id: UUID, claimedAt: Instant?, error: String?): Boolean = true
        override suspend fun countByState(state: String): Long = 0
        override suspend fun reclaimStale(olderThan: Duration): Int = 0
        override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = 0
        override suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int = 0
    }

    private class DirectTransactionRunner : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun `a failed forward logs no raw throwable`() = runBlocking {
        startCapture()
        val message = InboxMessage(
            id = UUID.randomUUID(),
            source = "stripe",
            idempotencyKey = "evt_1",
            eventType = "payment.succeeded",
            payload = JsonObject(emptyMap())
        )
        val relay = InboxRelay(
            config = InboxRelayConfig(),
            inboxRepository = SingleMessageInbox(mutableListOf(message)),
            outboxRepository = SecretLeakingOutbox(),
            transactionRunner = DirectTransactionRunner(),
            sourceTopicTemplates = emptyMap()
        )

        relay.relayBatch()

        val event = appender.list.single { it.level == Level.ERROR && it.message.contains("Forwarding inbox message") }
        assertNull(event.throwableProxy, "The log site must not attach the throwable.")
        val rendered = event.formattedMessage
        assertTrue(
            rendered.contains("[REDACTED]"),
            "The reason must reach the log sanitised. rendered=$rendered"
        )
        assertTrue(
            !rendered.contains("super-secret-token"),
            "The credential of the cause must never reach the log. rendered=$rendered"
        )
    }
}
