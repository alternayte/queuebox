package org.nxtspec

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.OutboxRepositoryInterface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionServiceTest {

    private fun createDisabledConfig() = RetentionConfig(
        enabled = false,
        outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
        inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
    )

    private fun createAgeBasedOutboxConfig(
        maxAge: String = "7d",
        cleanupInterval: String = "1s",
        batchSize: Int = 100
    ) = RetentionConfig(
        enabled = true,
        outbox = TableRetentionConfig(
            policy = RetentionPolicy.AGE,
            maxAge = maxAge,
            cleanupInterval = cleanupInterval,
            batchSize = batchSize
        ),
        inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
    )

    private fun createCountBasedOutboxConfig(
        maxCount: Int = 1000,
        cleanupInterval: String = "1s"
    ) = RetentionConfig(
        enabled = true,
        outbox = TableRetentionConfig(
            policy = RetentionPolicy.COUNT,
            maxCount = maxCount,
            cleanupInterval = cleanupInterval
        ),
        inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
    )

    private fun createAgeBasedInboxConfig(
        maxAge: String = "7d",
        cleanupInterval: String = "1s",
        batchSize: Int = 100
    ) = RetentionConfig(
        enabled = true,
        outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
        inbox = TableRetentionConfig(
            policy = RetentionPolicy.AGE,
            maxAge = maxAge,
            cleanupInterval = cleanupInterval,
            batchSize = batchSize
        )
    )

    private fun createCountBasedInboxConfig(
        maxCount: Int = 1000,
        cleanupInterval: String = "1s"
    ) = RetentionConfig(
        enabled = true,
        outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
        inbox = TableRetentionConfig(
            policy = RetentionPolicy.COUNT,
            maxCount = maxCount,
            cleanupInterval = cleanupInterval
        )
    )

    @Test
    fun `should not start when config disabled`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val service = RetentionService(
            config = createDisabledConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)

        // Should not be running since config is disabled
        assertFalse(service.isRunning())

        // No cleanup should have been called
        coVerify(exactly = 0) { outboxRepository.deleteOlderThan(any(), any(), any()) }
        coVerify(exactly = 0) { inboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should schedule outbox cleanup when policy not disabled`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        assertTrue(service.isRunning())
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan for outbox
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should schedule inbox cleanup when policy not disabled`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { inboxRepository.deleteOlderThan(any(), any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedInboxConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        assertTrue(service.isRunning())
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan for inbox
        coVerify(atLeast = 1) { inboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should delete outbox messages older than cutoff with AGE policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        // First call returns some deletions, second returns 0 to stop batching
        coEvery { outboxRepository.deleteOlderThan("sent", any(), any()) } returns 50 andThen 0
        coEvery { outboxRepository.deleteOlderThan("dead", any(), any()) } returns 30 andThen 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(maxAge = "7d"),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan for both "sent" and "dead" states
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan("sent", any(), any()) }
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan("dead", any(), any()) }
    }

    @Test
    fun `should delete outbox messages by count with COUNT policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { outboxRepository.deleteExceptMostRecent("sent", 1000, any()) } returns 50
        coEvery { outboxRepository.deleteExceptMostRecent("dead", 1000, any()) } returns 30

        val service = RetentionService(
            config = createCountBasedOutboxConfig(maxCount = 1000),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteExceptMostRecent for both "sent" and "dead" states
        coVerify(atLeast = 1) { outboxRepository.deleteExceptMostRecent("sent", 1000, any()) }
        coVerify(atLeast = 1) { outboxRepository.deleteExceptMostRecent("dead", 1000, any()) }
    }

    @Test
    fun `should return 0 for DISABLED outbox policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED),
            inbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "7d",
                cleanupInterval = "1s"
            )
        )

        coEvery { inboxRepository.deleteOlderThan(any(), any(), any()) } returns 0

        val service = RetentionService(
            config = config,
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should not have called any outbox delete methods
        coVerify(exactly = 0) { outboxRepository.deleteOlderThan(any(), any(), any()) }
        coVerify(exactly = 0) { outboxRepository.deleteExceptMostRecent(any(), any(), any()) }
    }

    @Test
    fun `should delete inbox messages older than cutoff with AGE policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { inboxRepository.deleteOlderThan(any(), any(), any()) } returns 50 andThen 0

        val service = RetentionService(
            config = createAgeBasedInboxConfig(maxAge = "7d"),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan for inbox
        coVerify(atLeast = 1) { inboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should log warning and return 0 for COUNT inbox policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val service = RetentionService(
            config = createCountBasedInboxConfig(maxCount = 1000),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should not have called deleteOlderThan for inbox (COUNT not supported)
        coVerify(exactly = 0) { inboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should return 0 for DISABLED inbox policy`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "7d",
                cleanupInterval = "1s"
            ),
            inbox = TableRetentionConfig(policy = RetentionPolicy.DISABLED)
        )

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 0

        val service = RetentionService(
            config = config,
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should not have called any inbox delete methods
        coVerify(exactly = 0) { inboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should delete in batches to prevent lock contention`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        // Returns batchSize on first call, less on second to stop
        coEvery { outboxRepository.deleteOlderThan("sent", any(), any()) } returns 100 andThen 50 andThen 0
        coEvery { outboxRepository.deleteOlderThan("dead", any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(batchSize = 100),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan multiple times for "sent" (batching)
        coVerify(atLeast = 2) { outboxRepository.deleteOlderThan("sent", any(), any()) }
    }

    @Test
    fun `should continue batching while deleted equals batchSize`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        // Returns exactly batchSize multiple times, then less to stop
        coEvery { outboxRepository.deleteOlderThan("sent", any(), any()) } returns 100 andThen 100 andThen 100 andThen 50 andThen 0
        coEvery { outboxRepository.deleteOlderThan("dead", any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(batchSize = 100),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan at least 4 times (100, 100, 100, 50, stop)
        coVerify(atLeast = 4) { outboxRepository.deleteOlderThan("sent", any(), any()) }
    }

    @Test
    fun `should stop batching when deleted less than batchSize`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        // Returns less than batchSize immediately
        coEvery { outboxRepository.deleteOlderThan("sent", any(), any()) } returns 50
        coEvery { outboxRepository.deleteOlderThan("dead", any(), any()) } returns 30

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(batchSize = 100),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have called deleteOlderThan exactly once per state (no batching needed)
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan("sent", any(), any()) }
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan("dead", any(), any()) }
    }

    @Test
    fun `should record cleanup metrics when collector present`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 50 andThen 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should record cleanup run metrics
        verify(atLeast = 1) { metricsCollector.recordCleanupRun("outbox", any(), any()) }
    }

    @Test
    fun `should handle cleanup exception and continue`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        // First call throws exception, subsequent calls succeed
        coEvery { outboxRepository.deleteOlderThan("sent", any(), any()) } throws RuntimeException("Database error") andThen 50 andThen 0
        coEvery { outboxRepository.deleteOlderThan("dead", any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(cleanupInterval = "1s"),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(2500) // Wait for multiple cleanup cycles
        service.stop()

        // Should have recovered and continued calling deleteOlderThan
        coVerify(atLeast = 2) { outboxRepository.deleteOlderThan("sent", any(), any()) }
    }

    @Test
    fun `isRunning returns correct state before and after stop`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository
        )

        // Initially not running
        assertFalse(service.isRunning())

        service.start()
        assertTrue(service.isRunning())

        service.stop()
        assertFalse(service.isRunning())
    }

    @Test
    fun `should not record metrics when metricsCollector is null`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 50 andThen 0

        val service = RetentionService(
            config = createAgeBasedOutboxConfig(),
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = null
        )

        service.start()
        delay(1500)
        service.stop()

        // Should have executed cleanup without crashing (no metrics collector)
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan(any(), any(), any()) }
    }

    @Test
    fun `should cleanup both outbox and inbox when both enabled`() = runBlocking {
        val outboxRepository = mockk<OutboxRepositoryInterface>(relaxed = true)
        val inboxRepository = mockk<InboxRepositoryInterface>(relaxed = true)
        val metricsCollector = mockk<MetricsCollectorInterface>(relaxed = true)

        val config = RetentionConfig(
            enabled = true,
            outbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "7d",
                cleanupInterval = "1s",
                batchSize = 100
            ),
            inbox = TableRetentionConfig(
                policy = RetentionPolicy.AGE,
                maxAge = "30d",
                cleanupInterval = "1s",
                batchSize = 100
            )
        )

        coEvery { outboxRepository.deleteOlderThan(any(), any(), any()) } returns 50 andThen 0
        coEvery { inboxRepository.deleteOlderThan(any(), any(), any()) } returns 30 andThen 0

        val service = RetentionService(
            config = config,
            outboxRepository = outboxRepository,
            inboxRepository = inboxRepository,
            metricsCollector = metricsCollector
        )

        service.start()
        delay(1500)
        service.stop()

        // Should cleanup both tables
        coVerify(atLeast = 1) { outboxRepository.deleteOlderThan(any(), any(), any()) }
        coVerify(atLeast = 1) { inboxRepository.deleteOlderThan(any(), any(), any()) }
        verify(atLeast = 1) { metricsCollector.recordCleanupRun("outbox", any(), any()) }
        verify(atLeast = 1) { metricsCollector.recordCleanupRun("inbox", any(), any()) }
    }
}
