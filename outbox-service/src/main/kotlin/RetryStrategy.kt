package org.nxtspec

class RetryStrategy(private val config: OutboxConfig) {
    companion object {
        private const val MAX_DELAY_MS = 60_000L // 1 minute max
    }

    fun calculateDelay(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0)
        // Exponential backoff: base_delay * 2^attempt
        val exponentialDelay = config.retryBaseDelayMs * (1L shl safeAttempt.coerceAtMost(20))
        // Add jitter of 0 to 25% of the exponential delay
        val jitter = (0..(exponentialDelay / 4).coerceAtLeast(1)).random()
        return (exponentialDelay + jitter).coerceAtMost(MAX_DELAY_MS)
    }

    fun shouldRetry(attempt: Int, maxAttempts: Int): Boolean = attempt < maxAttempts
}
