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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 5L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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

        coVerify { repository.claimBatch(defaultConfig.batchSize) }
        coVerify { publisher.publish(message, destination) }
    }

    @Test
    fun `should mark message as dead when no route found`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val message = createTestMessage()

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
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

        coVerify { repository.markDead(message.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
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

        coVerify { repository.markDead(message.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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

        coVerify { repository.markSent(message.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.failure(RuntimeException("Connection failed"))

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

        coVerify { repository.scheduleRetry(message.id, any()) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.failure(RuntimeException("Connection failed"))

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

        coVerify { repository.markDead(message.id) }
        verify { metricsCollector.recordMessageDead() }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns TransformResult.Success(transformedPayload)

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
        coVerify { publisher.publish(match { it.payload == transformedPayload }, destination) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns TransformResult.Success(transformedPayload)

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

        coVerify { repository.markSent(message.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns TransformResult.Error("Transform failed")

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

        coVerify { repository.scheduleRetry(message.id, any()) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns TransformResult.DeadLetter("Fatal error")

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

        coVerify { repository.markDead(message.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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
        coVerify { publisher.publish(message, destination) }
    }

    @Test
    fun `should update pending count metric when metricsCollector present`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { repository.claimBatch(any()) } returns emptyList()
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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
        coEvery { repository.claimBatch(any()) } throws RuntimeException("Database error") andThen listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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
        coVerify { repository.markSent(message.id) }
    }

    @Test
    fun `should stop polling when shutdown called`() = runBlocking {
        val repository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val router = mockk<MessageRouter>()
        val publisher = mockk<Publisher>()

        coEvery { repository.claimBatch(any()) } returns emptyList()
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

        coEvery { repository.claimBatch(any()) } returns emptyList()
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)
        coEvery { transformPipeline.transform(any(), any(), any(), any()) } returns TransformResult.Success(transformedPayload)

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

        coEvery { repository.claimBatch(any()) } returns listOf(message1, message2) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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

        coVerify { repository.markSent(message1.id) }
        coVerify { repository.markSent(message2.id) }
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

        coEvery { repository.claimBatch(any()) } returns listOf(message) andThen emptyList()
        coEvery { repository.countByState("pending") } returns 0L
        every { router.route(any(), any()) } returns routingResult
        every { publisher.supports(any()) } returns true
        coEvery { publisher.publish(any(), any()) } returns Result.success(Unit)

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
        coVerify { publisher.publish(message, destination) }
    }
}
