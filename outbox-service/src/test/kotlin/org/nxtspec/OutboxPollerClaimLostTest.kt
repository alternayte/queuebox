package org.nxtspec

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import java.util.UUID
import kotlin.test.Test

/**
 * Seventh review gate. The poller must observe a lost claim on every terminal write.
 *
 * The publish runs before the mark. A lost claim on `markSent` therefore means that the
 * destination already holds the message twice, because the new owner publishes it again. The
 * poller cannot undo that delivery. It must report the loss and must leave the row to the new
 * owner. It must never publish again, and it must never rewrite the state of the new owner.
 */
class OutboxPollerClaimLostTest {

    private val config = OutboxConfig(
        pollIntervalMs = 50,
        batchSize = 10,
        retryBaseDelayMs = 1000,
        maxAttempts = 5
    )

    private val retryStrategy = RetryStrategy(config)

    private fun message(attempt: Int = 0, maxAttempts: Int = 5) = OutboxMessage(
        id = UUID.randomUUID(),
        topic = "test.topic",
        payload = JsonObject(mapOf("data" to JsonPrimitive("test"))),
        attempt = attempt,
        maxAttempts = maxAttempts,
        createdAt = Clock.System.now()
    )

    private fun runPoller(
        repository: OutboxRepositoryInterface,
        router: MessageRouter,
        publisher: Publisher,
        metrics: MetricsCollectorInterface
    ) = runBlocking {
        val poller = OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metrics
        )
        poller.start()
        delay(150)
        poller.shutdown()
    }

    @Test
    fun `a lost claim on markSent records the loss and counts no send`() {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metrics = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = message()
        val destination = Destination.Http(name = "test-dest", baseUrl = "http://example.com", path = "/webhook")

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        coEvery { repository.markSent(any(), any()) } returns false
        every { router.route(any(), any()) } returns RoutingResult(destination, "test.topic")
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        runPoller(repository, router, publisher, metrics)

        verify { metrics.recordClaimLost("outbox") }
        verify(exactly = 0) { metrics.recordMessageSent() }
        coVerify(exactly = 0) { repository.scheduleRetry(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.markDead(any(), any(), any()) }
    }

    @Test
    fun `a lost claim on scheduleRetry records the loss and counts no failure`() {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metrics = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = message()
        val destination = Destination.Http(name = "test-dest", baseUrl = "http://example.com", path = "/webhook")

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns false
        every { router.route(any(), any()) } returns RoutingResult(destination, "test.topic")
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.failure(RuntimeException("boom"))

        runPoller(repository, router, publisher, metrics)

        verify { metrics.recordClaimLost("outbox") }
        verify(exactly = 0) { metrics.recordMessageFailed() }
    }

    @Test
    fun `a lost claim on markDead records the loss and counts no dead letter`() {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metrics = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = message()

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        coEvery { repository.markDead(any(), any(), any()) } returns false
        every { router.route(any(), any()) } returns null

        runPoller(repository, router, publisher, metrics)

        verify { metrics.recordClaimLost("outbox") }
        verify(exactly = 0) { metrics.recordMessageDead() }
    }

    @Test
    fun `a claim that the poller still owns marks the message sent`() {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metrics = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = message()
        val destination = Destination.Http(name = "test-dest", baseUrl = "http://example.com", path = "/webhook")

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        coEvery { repository.markSent(any(), any()) } returns true
        every { router.route(any(), any()) } returns RoutingResult(destination, "test.topic")
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        runPoller(repository, router, publisher, metrics)

        verify { metrics.recordMessageSent() }
        verify(exactly = 0) { metrics.recordClaimLost(any()) }
    }
}
