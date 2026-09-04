package org.nxtspec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.nxtspec.logging.LogKeys
import org.nxtspec.logging.withLogContext
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers F-046. The mapped diagnostic context must survive a coroutine dispatch, and it must not
 * leak an entry to an unrelated coroutine.
 */
class LogContextTest {

    @Test
    fun `the context survives a suspension point and a thread change`() = runBlocking {
        val seen = withLogContext(LogKeys.MESSAGE_ID to "m-1", LogKeys.TOPIC to "order.created") {
            delay(20)
            withContext(Dispatchers.IO) {
                delay(20)
                MDC.get(LogKeys.MESSAGE_ID) to MDC.get(LogKeys.TOPIC)
            }
        }

        assertEquals("m-1" to "order.created", seen)
    }

    @Test
    fun `the context does not leak after the block returns`() = runBlocking {
        withLogContext(LogKeys.MESSAGE_ID to "m-2") {
            delay(10)
        }

        assertNull(MDC.get(LogKeys.MESSAGE_ID), "The entry must not stay behind")
    }

    @Test
    fun `a null value adds no entry`() = runBlocking {
        val seen = withLogContext(LogKeys.CORRELATION_ID to null) {
            MDC.get(LogKeys.CORRELATION_ID)
        }

        assertNull(seen)
    }

    @Test
    fun `an inner block keeps the outer entries`() = runBlocking {
        val seen = withLogContext(LogKeys.SOURCE to "stripe") {
            withLogContext(LogKeys.MESSAGE_ID to "m-3") {
                MDC.get(LogKeys.SOURCE) to MDC.get(LogKeys.MESSAGE_ID)
            }
        }

        assertEquals("stripe" to "m-3", seen)
    }

    @Test
    fun `a control character cannot forge a second log line`() = runBlocking {
        val seen = withLogContext(LogKeys.CORRELATION_ID to "abc\nERROR forged line") {
            MDC.get(LogKeys.CORRELATION_ID)
        }

        assertEquals("abc ERROR forged line", seen)
    }
}
