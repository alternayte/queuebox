package org.nxtspec

import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.nxtspec.transform.TransformContext
import org.nxtspec.transform.TransformEngine
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Concurrency test for the bounded expression cache in TransformEngine.
 *
 * The test fills the cache past its maximum size from eight threads for five
 * seconds. It asserts that no exception escapes and that the cache size never
 * exceeds the configured bound.
 */
class TransformEngineCacheTest {

    @Test
    fun `cache stays within bound under sustained concurrent load`() {
        val maxCacheSize = 10
        val engine = TransformEngine(maxCacheSize = maxCacheSize)
        val threadCount = 8
        val runDurationSeconds = 5L
        val exceptionCaught = AtomicBoolean(false)
        val expressionCounter = AtomicInteger(0)
        val stop = AtomicBoolean(false)

        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val failureSeen = java.util.concurrent.atomic.AtomicBoolean(false)

            repeat(threadCount) { threadIndex ->
                executor.submit {
                    try {
                        startLatch.await()
                        val context = TransformContext(
                            messageId = UUID.randomUUID(),
                            topic = "test.topic",
                            attempt = 1,
                            timestamp = Clock.System.now()
                        )
                        while (!stop.get()) {
                            val expressionIndex = expressionCounter.getAndIncrement()
                            val expression = "\$number($threadIndex) + $expressionIndex"
                            val payload = kotlinx.serialization.json.JsonObject(emptyMap())
                            val result = engine.evaluate(expression, payload, context)
                            // evaluate wraps its body in runCatching, so a failure arrives as a
                            // failed Result rather than as a thrown exception.
                            if (result.isFailure) failureSeen.set(true)
                        }
                    } catch (e: Throwable) {
                        exceptionCaught.set(true)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            Thread.sleep(TimeUnit.SECONDS.toMillis(runDurationSeconds))
            stop.set(true)
            doneLatch.await()
            executor.shutdown()

            assertFalse(exceptionCaught.get(), "No exception must escape while the cache fills concurrently.")
            assertFalse(failureSeen.get(), "No evaluation must fail while the cache fills concurrently.")
            assertTrue(
                engine.cacheSize() <= maxCacheSize,
                "Cache size ${engine.cacheSize()} must not exceed the configured bound $maxCacheSize."
            )
        }
    }
}
