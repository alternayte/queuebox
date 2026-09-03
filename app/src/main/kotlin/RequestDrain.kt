package org.nxtspec.app

import io.ktor.server.application.*
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the requests that the server currently handles. See F-029.
 *
 * The Ktor stop cancels an in-flight handler, so the shutdown must first wait for the
 * outstanding requests. Without the wait an inbox request that already returned 202 to the
 * caller can lose its database write, and a slow request answers with an error.
 */
class RequestDrain {
    private val inFlight = AtomicInteger(0)

    fun count(): Int = inFlight.get()

    fun enter() {
        inFlight.incrementAndGet()
    }

    fun exit() {
        inFlight.decrementAndGet()
    }

    /**
     * Waits until no request is in flight, or until the timeout expires.
     *
     * @return true when every request finished
     */
    suspend fun await(timeoutMs: Long, pollMs: Long = 25): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.get() == 0) return true
            delay(pollMs)
        }
        return inFlight.get() == 0
    }
}

/**
 * Installs the request counter that the shutdown drains.
 */
fun Application.configureRequestDrain(drain: RequestDrain) {
    intercept(ApplicationCallPipeline.Setup) {
        drain.enter()
        try {
            proceed()
        } finally {
            drain.exit()
        }
    }
}
