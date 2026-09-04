package org.nxtspec.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxMessage
import org.nxtspec.OutboxPoller
import org.nxtspec.PublishContext
import org.nxtspec.Publisher
import org.nxtspec.RetryStrategy
import org.nxtspec.RoutingResult
import org.nxtspec.repository.OutboxRepositoryInterface
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers F-046. QueueBox logs through SLF4J, and a failed publish names the message.
 */
class LoggingTest {

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

    private class FailingPublisher : Publisher {
        override fun supports(destination: Destination): Boolean = true
        override suspend fun publish(
            message: OutboxMessage,
            destination: Destination,
            context: PublishContext
        ): Result<Unit> = Result.failure(RuntimeException("destination refused the message"))
    }

    @Test
    fun `a failed publish emits one warn line that names the message`() = runBlocking {
        startCapture()

        val messageId = UUID.randomUUID()
        val message = OutboxMessage(
            id = messageId,
            topic = "order.created",
            payload = JsonObject(emptyMap()),
            attempt = 0,
            maxAttempts = 5,
            createdAt = Clock.System.now()
        )
        val destination = Destination.Http(name = "dest", baseUrl = "https://example.com", path = "/h")

        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.reclaimStale(any()) } returns 0

        val router = mockk<MessageRouter>()
        every { router.route(any(), any()) } returns RoutingResult(destination, null)

        val config = OutboxConfig(pollIntervalMs = 30, batchSize = 10, maxAttempts = 5)
        val poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(FailingPublisher()),
            retryStrategy = RetryStrategy(config)
        )

        poller.start()
        repeat(50) {
            if (appender.list.any { it.level == Level.WARN }) return@repeat
            delay(20)
        }
        poller.shutdown()

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertTrue(warnings.isNotEmpty(), "A failed publish must emit a WARN line")
        assertTrue(
            warnings.any { it.formattedMessage.contains(messageId.toString()) },
            "The WARN line must name the message identifier. Saw: " +
                warnings.map { it.formattedMessage }
        )
    }

    @Test
    fun `the message context reaches the mapped diagnostic context`() = runBlocking {
        startCapture()

        val messageId = UUID.randomUUID()
        val message = OutboxMessage(
            id = messageId,
            topic = "order.created",
            payload = JsonObject(emptyMap()),
            createdAt = Clock.System.now()
        )
        val destination = Destination.Http(name = "dest", baseUrl = "https://example.com", path = "/h")

        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.reclaimStale(any()) } returns 0

        val router = mockk<MessageRouter>()
        every { router.route(any(), any()) } returns RoutingResult(destination, null)

        val config = OutboxConfig(pollIntervalMs = 30, batchSize = 10, maxAttempts = 5)
        val poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(FailingPublisher()),
            retryStrategy = RetryStrategy(config)
        )

        poller.start()
        repeat(50) {
            if (appender.list.any { it.level == Level.WARN }) return@repeat
            delay(20)
        }
        poller.shutdown()

        val warning = appender.list.first { it.level == Level.WARN }

        assertTrue(
            warning.mdcPropertyMap["messageId"] == messageId.toString(),
            "The mapped diagnostic context must carry the message identifier. Saw: " +
                warning.mdcPropertyMap
        )
        assertTrue(warning.mdcPropertyMap["topic"] == "order.created")
    }
}
