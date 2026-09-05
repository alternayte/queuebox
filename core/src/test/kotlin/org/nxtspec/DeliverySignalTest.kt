package org.nxtspec

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class DeliverySignalTest {

    private fun elapsed(block: suspend CoroutineScope.() -> Unit): Long {
        val started = System.currentTimeMillis()
        runBlocking { block() }
        return System.currentTimeMillis() - started
    }

    @Test
    fun `a wake ends the wait early`() {
        val signal = DeliverySignal()
        val took = elapsed {
            launch { signal.wake() }
            signal.await(5000)
        }
        assertTrue(took < 2000, "the wait must end on the wake, took $took ms")
    }

    @Test
    fun `a wake before the wait is not lost`() {
        val signal = DeliverySignal()
        signal.wake()
        val took = elapsed { signal.await(5000) }
        assertTrue(took < 2000, "the retained wake must end the wait, took $took ms")
    }

    @Test
    fun `many wakes conflate into one`() {
        val signal = DeliverySignal()
        repeat(100) { signal.wake() }
        // The conflated channel holds one element only, so the second wait runs to its
        // timeout instead of returning ninety-nine more times.
        val took = elapsed {
            signal.await(5000)
            signal.await(300)
        }
        assertTrue(took >= 250, "the second wait must reach its timeout, took $took ms")
    }

    @Test
    fun `the wait always ends without a wake`() {
        val took = elapsed { DeliverySignal().await(200) }
        assertTrue(took >= 150, "the timeout must apply, took $took ms")
    }
}
