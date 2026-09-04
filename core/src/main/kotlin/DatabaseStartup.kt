package org.nxtspec

import org.nxtspec.logging.logger
import java.sql.Connection
import javax.sql.DataSource

/**
 * Thrown when the database does not answer inside the startup timeout.
 */
class DatabaseUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Waits for the database at startup. See F-056.
 *
 * The pool creates a connection lazily, so a data source can exist while the database is still
 * starting. Without this wait the first query throws, the process exits, and an orchestrator
 * shows a crash loop with no useful message. The wait retries with backoff and logs one clear
 * line per attempt.
 */
object DatabaseStartup {

    private val log = logger("org.nxtspec.DatabaseStartup")

    private const val FIRST_DELAY_MS = 500L
    private const val MAX_DELAY_MS = 5000L

    /**
     * Blocks until one connection succeeds, or until the timeout expires.
     *
     * @param timeoutMs the whole budget for the wait
     * @param sleep the sleep function, replaced by a test
     * @param now the clock, replaced by a test
     * @throws DatabaseUnavailableException when no attempt succeeds inside the budget
     */
    fun awaitConnection(
        dataSource: DataSource,
        timeoutMs: Long,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = { System.currentTimeMillis() }
    ) {
        val deadline = now() + timeoutMs
        var delayMs = FIRST_DELAY_MS
        var attempt = 0
        var lastFailure: Exception? = null

        while (true) {
            attempt++
            try {
                dataSource.connection.use { connection -> validate(connection) }
                if (attempt > 1) {
                    log.info("The database answered on attempt {}.", attempt)
                }
                return
            } catch (e: Exception) {
                lastFailure = e
                val remaining = deadline - now()
                if (remaining <= 0) break

                val wait = minOf(delayMs, remaining)
                log.warn(
                    "The database did not answer on attempt {}. QueueBox retries in {} ms. " +
                        "Reason: {}",
                    attempt,
                    wait,
                    e.message
                )
                sleep(wait)
                delayMs = minOf(delayMs * 2, MAX_DELAY_MS)
            }
        }

        throw DatabaseUnavailableException(
            "The database did not answer inside $timeoutMs ms, after $attempt attempt(s). " +
                "Check 'database.url', the credentials, and the network. Raise " +
                "'database.startupTimeoutMs' when the database needs longer to start.",
            lastFailure
        )
    }

    private fun validate(connection: Connection) {
        // isValid asks the driver, which is cheaper than a statement and works on every database.
        require(connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
            "The connection is not valid"
        }
    }

    private const val VALIDATION_TIMEOUT_SECONDS = 5
}
