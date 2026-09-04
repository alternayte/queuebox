package org.nxtspec.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
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

    @Volatile
    private var draining = false

    fun count(): Int = inFlight.get()

    fun isDraining(): Boolean = draining

    /**
     * Refuses every new request from now on.
     *
     * Without this step the count never reaches zero under steady traffic, and a request that
     * arrives during the drain is cancelled by the server stop.
     */
    fun startDraining() {
        draining = true
    }

    /**
     * Registers one request.
     *
     * @return false when the drain started, which means the caller must refuse the request
     */
    fun enter(): Boolean {
        if (draining) return false
        inFlight.incrementAndGet()
        return true
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
        if (!drain.enter()) {
            call.respondText(
                text = """{"error":"QueueBox is shutting down"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable
            )
            finish()
            return@intercept
        }
        try {
            proceed()
        } finally {
            drain.exit()
        }
    }
}
