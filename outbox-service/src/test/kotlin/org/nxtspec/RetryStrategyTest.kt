package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryStrategyTest {

    @Test
    fun `should return base delay with jitter when first attempt`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(0)

        // Base delay is 1000, jitter is 0-25% (0-250), so range is 1000-1250
        assertTrue(delay in 1000..1250, "Delay $delay should be between 1000 and 1250")
    }

    @Test
    fun `should double delay when second attempt`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(1)

        // 2000 base + up to 500 jitter = 2000-2500
        assertTrue(delay in 2000..2500, "Delay $delay should be between 2000 and 2500")
    }

    @Test
    fun `should quadruple delay when third attempt`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(2)

        // 4000 base + up to 1000 jitter = 4000-5000
        assertTrue(delay in 4000..5000, "Delay $delay should be between 4000 and 5000")
    }

    @Test
    fun `should cap at max delay when many attempts`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(10)

        // Should cap at MAX_DELAY_MS (60,000)
        assertTrue(delay <= 60_000, "Delay $delay should be at most 60000")
    }

    @Test
    fun `should handle negative attempt by treating as zero`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(-5)

        // Should be same as attempt 0: 1000-1250
        assertTrue(delay in 1000..1250, "Delay $delay should be between 1000 and 1250")
    }

    @Test
    fun `should use custom base delay from config`() {
        val config = OutboxConfig(retryBaseDelayMs = 500)
        val strategy = RetryStrategy(config)

        val delay = strategy.calculateDelay(0)

        // 500 base + up to 125 jitter = 500-625
        assertTrue(delay in 500..625, "Delay $delay should be between 500 and 625")
    }

    @Test
    fun `should return true when attempt below max`() {
        val config = OutboxConfig()
        val strategy = RetryStrategy(config)

        assertTrue(strategy.shouldRetry(0, 5))
        assertTrue(strategy.shouldRetry(1, 5))
        assertTrue(strategy.shouldRetry(3, 5))
        assertTrue(strategy.shouldRetry(4, 5))
    }

    @Test
    fun `should return false when attempt at max`() {
        val config = OutboxConfig()
        val strategy = RetryStrategy(config)

        assertFalse(strategy.shouldRetry(5, 5))
    }

    @Test
    fun `should return false when attempt exceeds max`() {
        val config = OutboxConfig()
        val strategy = RetryStrategy(config)

        assertFalse(strategy.shouldRetry(6, 5))
        assertFalse(strategy.shouldRetry(100, 5))
    }

    @Test
    fun `should handle zero max attempts`() {
        val config = OutboxConfig()
        val strategy = RetryStrategy(config)

        // With maxAttempts = 0, no retries should be allowed
        assertFalse(strategy.shouldRetry(0, 0))
    }

    @Test
    fun `should produce jitter within expected range across multiple calls`() {
        val config = OutboxConfig(retryBaseDelayMs = 1000)
        val strategy = RetryStrategy(config)

        // Run multiple times and verify jitter is within 0-25%
        repeat(100) {
            val delay = strategy.calculateDelay(0)
            assertTrue(
                delay in 1000..1250,
                "Delay $delay should always be between 1000 and 1250"
            )
        }
    }
}
