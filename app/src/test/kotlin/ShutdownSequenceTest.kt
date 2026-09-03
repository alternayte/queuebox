package org.nxtspec.app

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers F-029. The shutdown order and the request drain.
 */
class ShutdownSequenceTest {

    @Test
    fun `runs the steps in order`() = runBlocking {
        val order = mutableListOf<String>()

        ShutdownSequence(
            stopServer = { delay(1); order.add("server") },
            stopBackgroundServices = { delay(1); order.add("services") },
            closeResources = { delay(1); order.add("resources") },
            log = { }
        ).run()

        assertEquals(listOf("server", "services", "resources"), order)
    }

    @Test
    fun `runs the remaining steps when one step fails`() = runBlocking {
        val order = mutableListOf<String>()
        val logged = mutableListOf<String>()

        ShutdownSequence(
            stopServer = { delay(1); throw IllegalStateException("server stop failed") },
            stopBackgroundServices = { delay(1); order.add("services") },
            closeResources = { delay(1); order.add("resources") },
            log = { logged.add(it) }
        ).run()

        assertEquals(listOf("services", "resources"), order)
        assertTrue(logged.any { it.contains("HTTP server") && it.contains("server stop failed") })
        assertTrue(logged.any { it.contains("Shutdown complete") })
    }

    @Test
    fun `drain reports no request when nothing is in flight`() = runBlocking {
        val drain = RequestDrain()

        assertEquals(0, drain.count())
        assertTrue(drain.await(100))
    }

    @Test
    fun `drain waits until the last request leaves`() = runBlocking {
        val drain = RequestDrain()
        drain.enter()
        drain.enter()
        assertEquals(2, drain.count())

        val waiting = async { drain.await(5000) }
        delay(50)
        drain.exit()
        delay(50)
        drain.exit()

        assertTrue(waiting.await())
        assertEquals(0, drain.count())
    }

    @Test
    fun `drain gives up after the timeout`() = runBlocking {
        val drain = RequestDrain()
        drain.enter()

        assertFalse(drain.await(150))
        assertEquals(1, drain.count())
    }
}
