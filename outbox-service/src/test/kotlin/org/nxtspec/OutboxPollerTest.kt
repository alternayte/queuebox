package org.nxtspec

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.transform.TransformPipeline
import org.nxtspec.transform.TransformResult
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxPollerTest {

    private val defaultConfig = OutboxConfig(
        pollIntervalMs = 50,
        batchSize = 10,
        retryBaseDelayMs = 1000,
        maxAttempts = 5
    )

    private val retryStrategy = RetryStrategy(defaultConfig)

    private fun createTestMessage(
        id: UUID = UUID.randomUUID(),
        topic: String = "test.topic",
        payload: kotlinx.serialization.json.JsonElement = JsonObject(mapOf("data" to JsonPrimitive("test"))),
        attempt: Int = 0,
        maxAttempts: Int = 5
    ) = OutboxMessage(
        id = id,
        topic = topic,
        payload = payload,
        attempt = attempt,
        maxAttempts = maxAttempts,
        createdAt = Clock.System.now()
    )

    private fun createHttpDestination(name: String = "test-dest") = Destination.Http(
        name = name,
        baseUrl = "http://example.com",
        path = "/webhook"
    )

    @Test
    fun `should claim and process batch of messages`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 5L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify {
            repository.claimBatch(
                minOf(defaultConfig.batchSize, defaultConfig.concurrency),
                defaultConfig.claimTimeoutMs
            )
        }
        coVerify { publisher.publish(message, destination, any()) }
    }

    @Test
    fun `should mark message as dead when no route found`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns null

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markDead(message.id, any(), any()) }
        verify { metricsCollector.recordMessageDead() }
        verify { metricsCollector.recordProcessingDuration(any()) }
    }

    @Test
    fun `should mark message as dead when no publisher supports destination`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns false

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markDead(message.id, any(), any()) }
        verify { metricsCollector.recordMessageDead() }
    }

    @Test
    fun `should publish message successfully and mark sent`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markSent(message.id, any()) }
        verify { metricsCollector.recordMessageSent() }
    }

    @Test
    fun `should handle publish failure and schedule retry`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage(attempt = 0)
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.failure(RuntimeException("Connection failed"))

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.scheduleRetry(message.id, any(), any(), any()) }
        verify { metricsCollector.recordMessageFailed() }
    }

    @Test
    fun `should mark dead after max retries exceeded`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage(attempt = 5, maxAttempts = 5)
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.failure(RuntimeException("Connection failed"))

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markDead(message.id, any(), any()) }
        verify { metricsCollector.recordMessageDead() }
    }

    @Test
    fun `should give the transform context attempt 0 on the first delivery`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage(attempt = 0)
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ transformed: true }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)
        val contexts = mutableListOf<org.nxtspec.transform.TransformContext>()

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), capture(contexts)) } returns
            TransformResult.Success(message.payload)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        // The column default is 0 and only scheduleRetry raises it. The documents state this.
        kotlin.test.assertEquals(0, contexts.first().attempt)
    }

    @Test
    fun `should apply transform when routeTransform configured`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ transformed: true }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)
        val transformedPayload = JsonObject(mapOf("transformed" to JsonPrimitive(true)))

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns
            TransformResult.Success(transformedPayload)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { transformPipeline.transform(message.payload, transformConfig, null, any()) }
        coVerify { publisher.publish(match { it.payload == transformedPayload }, destination, any()) }
    }

    @Test
    fun `should handle TransformResult Success`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ transformed: true }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)
        val transformedPayload = JsonObject(mapOf("transformed" to JsonPrimitive(true)))

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns
            TransformResult.Success(transformedPayload)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markSent(message.id, any()) }
        verify { metricsCollector.recordMessageSent() }
    }

    @Test
    fun `should schedule retry on TransformResult Error`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage(attempt = 0)
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ invalid }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns
            TransformResult.Error("Transform failed")

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.scheduleRetry(message.id, any(), any(), any()) }
        verify { metricsCollector.recordMessageFailed() }
    }

    @Test
    fun `should mark dead on TransformResult DeadLetter`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ invalid }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns
            TransformResult.DeadLetter("Fatal error")

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markDead(message.id, any(), any()) }
        verify { metricsCollector.recordMessageDead() }
    }

    @Test
    fun `should skip transform when pipeline is null`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()
        val destination = createHttpDestination()
        val transformConfig = TransformConfig(expression = "{ transformed: true }")
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = transformConfig)

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        // No transformPipeline provided
        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = null
        )

        poller.start()
        delay(150)
        poller.shutdown()

        // Original message payload used (not transformed)
        coVerify { publisher.publish(message, destination, any()) }
    }

    @Test
    fun `should update pending count metric when metricsCollector present`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 42L

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.countByState("pending") }
        verify { metricsCollector.updatePendingCount(42L) }
    }

    @Test
    fun `should not record metrics when metricsCollector is null`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        // No metricsCollector provided
        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = null
        )

        poller.start()
        delay(150)
        poller.shutdown()

        // Verify repository countByState not called (no metrics collector)
        coVerify(exactly = 0) { repository.countByState(any()) }
    }

    @Test
    fun `should continue polling after exception in processBatch`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        // First call throws exception, second call returns message
        coEvery {
            repository.claimBatch(any(), any())
        } throws RuntimeException("Database error") andThen listOf(message) andThen
            emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(250) // Two poll intervals
        poller.shutdown()

        // Should have recovered and processed message
        coVerify { repository.markSent(message.id, any()) }
    }

    @Test
    fun `should stop polling when shutdown called`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        poller.start()
        assertTrue(poller.isRunning())

        poller.shutdown()
        assertFalse(poller.isRunning())
    }

    @Test
    fun `isRunning returns correct state`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        // Initially running is true (set at construction)
        assertTrue(poller.isRunning())

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L

        poller.start()
        assertTrue(poller.isRunning())

        poller.shutdown()
        assertFalse(poller.isRunning())
    }

    @Test
    fun `should apply destinationTransform when configured`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        val destTransformConfig = TransformConfig(expression = "{ fromDest: true }")
        val routingResult = RoutingResult(destination, "test.topic", destinationTransform = destTransformConfig)
        val transformedPayload = JsonObject(mapOf("fromDest" to JsonPrimitive(true)))

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns
            TransformResult.Success(transformedPayload)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { transformPipeline.transform(message.payload, null, destTransformConfig, any()) }
    }

    @Test
    fun `should process multiple messages in batch`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message1 = createTestMessage(topic = "topic.1")
        val message2 = createTestMessage(topic = "topic.2")
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "test.topic")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message1, message2) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(150)
        poller.shutdown()

        coVerify { repository.markSent(message1.id, any()) }
        coVerify { repository.markSent(message2.id, any()) }
    }

    @Test
    fun `should skip transform when no transform configs present`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)
        val transformPipeline = mockk<TransformPipeline>()

        val message = createTestMessage()
        val destination = createHttpDestination()
        // No transforms configured
        val routingResult = RoutingResult(destination, "test.topic", routeTransform = null, destinationTransform = null)

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector,
            transformPipeline = transformPipeline
        )

        poller.start()
        delay(150)
        poller.shutdown()

        // Transform pipeline should not be called
        coVerify(exactly = 0) { transformPipeline.transform(any(), any(), any(), any()) }
        // Original message should be used
        coVerify { publisher.publish(message, destination, any()) }
    }

    // --- F-006: stale claim recovery ---

    @Test
    fun `should reclaim stale claims on the first cycle`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 3

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = emptyList(),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(200)
        poller.shutdown()

        coVerify(atLeast = 1) { repository.reclaimStale(any()) }
        verify(atLeast = 1) { metricsCollector.recordMessageReclaimed(3) }
    }

    @Test
    fun `should reclaim at most once per claim timeout fifth`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>(relaxed = true)

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0

        // claimTimeoutMs of 300000 gives a reclaim interval of 60000 ms. The poll interval is
        // 50 ms, so many poll cycles run inside one reclaim interval.
        val poller = OutboxPoller(
            config = defaultConfig.copy(claimTimeoutMs = 300000),
            repository = repository,
            router = router,
            publishers = emptyList(),
            retryStrategy = retryStrategy
        )

        poller.start()
        delay(300)
        poller.shutdown()

        coVerify(exactly = 1) { repository.reclaimStale(any()) }
        coVerify(atLeast = 2) { repository.claimBatch(any(), any()) }
    }

    // --- F-004: the route routing key reaches the publisher ---

    @Test
    fun `should pass the routing key from the router to the publisher`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        val message = createTestMessage(topic = "order.created")
        val destination = createHttpDestination()
        val routingResult = RoutingResult(destination, "eu.high.order.created")

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(destination) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        poller.start()
        delay(200)
        poller.shutdown()

        coVerify {
            publisher.publish(any(), destination, PublishContext(routingKey = "eu.high.order.created"))
        }
    }

    // --- F-028: shutdown must be bounded ---

    @Test
    fun `shutdown should return within the timeout when a publisher never returns`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        val message = createTestMessage()
        val destination = createHttpDestination()

        coEvery { repository.claimBatch(any(), any()) } returns listOf(message) andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0
        every { router.route(any(), any()) } returns RoutingResult(destination, null)
        every { publisher.supports(destination) } returns true
        coEvery { publisher.publish(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            Result.success(Unit)
        }

        val poller = OutboxPoller(
            config = defaultConfig.copy(shutdownTimeoutMs = 1000),
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        poller.start()
        delay(200)

        // withTimeoutOrNull makes an unbounded shutdown a failure instead of a hang.
        var elapsed = 0L
        val returned = kotlinx.coroutines.withTimeoutOrNull(6000) {
            elapsed = kotlin.system.measureTimeMillis { poller.shutdown() }
            true
        }

        assertTrue(returned == true, "shutdown() did not return. It waited for the publisher.")
        assertTrue(elapsed < 5000, "shutdown() must return within the timeout. Took ${elapsed}ms")
        assertFalse(poller.isRunning())
    }

    // --- F-013: one failing message must not abort the batch ---

    @Test
    fun `should deliver the rest of the batch when one message fails`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val messages = (1..5).map { createTestMessage(topic = "test.topic.$it") }
        val failing = messages[2]
        val destination = createHttpDestination()

        coEvery { repository.claimBatch(any(), any()) } returns messages andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0
        every { router.route(any(), any()) } answers {
            if (firstArg<String>() == failing.topic) {
                error("router exploded")
            }
            RoutingResult(destination, null)
        }
        every { publisher.supports(destination) } returns true
        coEvery { publisher.publish(any(), any(), any()) } returns Result.success(Unit)

        val poller = OutboxPoller(
            config = defaultConfig.copy(concurrency = 1),
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(400)
        poller.shutdown()

        messages.filter { it.id != failing.id }.forEach { message ->
            coVerify { repository.markSent(message.id, any()) }
        }
        coVerify { repository.scheduleRetry(failing.id, any(), any(), any()) }
        verify(atLeast = 1) { metricsCollector.recordProcessError() }
    }

    // --- F-014: the batch publishes concurrently ---

    private class SlowPublisher(private val latencyMs: Long) : Publisher {
        val published = java.util.concurrent.atomic.AtomicInteger(0)
        override fun supports(destination: Destination): Boolean = true
        override suspend fun publish(
            message: OutboxMessage,
            destination: Destination,
            context: PublishContext
        ): Result<Unit> {
            delay(latencyMs)
            published.incrementAndGet()
            return Result.success(Unit)
        }
    }

    @Test
    fun `should publish a batch concurrently`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()

        val messages = (1..10).map { createTestMessage() }
        val destination = createHttpDestination()
        val publisher = SlowPublisher(latencyMs = 200)

        coEvery { repository.claimBatch(any(), any()) } returns messages andThen emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0
        every { router.route(any(), any()) } returns RoutingResult(destination, null)

        val poller = OutboxPoller(
            config = defaultConfig.copy(concurrency = 8),
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = retryStrategy
        )

        val elapsed = kotlin.system.measureTimeMillis {
            poller.start()
            while (publisher.published.get() < 10) {
                delay(10)
            }
        }
        poller.shutdown()

        assertTrue(
            elapsed < 1000,
            "Ten messages with 200 ms latency must finish in under one second. Took ${elapsed}ms"
        )
    }

    // --- F-015: the pending gauge query is rate limited ---

    @Test
    fun `should query the pending count at most once per gauge interval`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { repository.claimBatch(any(), any()) } returns emptyList()

        // Seventh review gate: the terminal write reports that the claim still holds.

        coEvery { repository.markSent(any(), any()) } returns true

        coEvery { repository.scheduleRetry(any(), any(), any(), any()) } returns true

        coEvery { repository.markDead(any(), any(), any()) } returns true
        coEvery { repository.reclaimStale(any()) } returns 0
        coEvery { repository.countByState("pending") } returns 0

        val poller = OutboxPoller(
            config = defaultConfig.copy(pollIntervalMs = 20, pendingGaugeIntervalMs = 5000),
            repository = repository,
            router = router,
            publishers = emptyList(),
            retryStrategy = retryStrategy,
            metricsCollector = metricsCollector
        )

        poller.start()
        delay(500)
        poller.shutdown()

        // The poll interval is 20 ms, so about 25 cycles ran inside one gauge interval.
        coVerify(exactly = 1) { repository.countByState("pending") }
    }
}
