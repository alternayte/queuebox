package org.nxtspec

import org.nxtspec.logging.logger
import java.sql.Connection
import javax.sql.DataSource

/**
 * Thrown when the database does not answer inside the startup timeout.
 */
/**
 * The database did not answer inside the startup budget.
 *
 * Seventh review gate, defect 3. This exception carries NO cause. A driver puts the JDBC URL, and
 * therefore the database password, in the message of the failure that caused it. Nothing above
 * `main` catches this, so the JVM prints the whole chain to the container log. The message carries
 * the sanitised text of the last failure instead.
 */
class DatabaseUnavailableException(message: String) : RuntimeException(message)

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
                    // Sixth review gate B2: the driver puts the JDBC URL, and therefore the
                    // database password, in this message. It fires on every start where the
                    // database is not yet up, which is the normal Compose path.
                    ErrorSanitizer.sanitize(e)
                )
                sleep(wait)
                delayMs = minOf(delayMs * 2, MAX_DELAY_MS)
            }
        }

        // Seventh review gate, defect 3. The retry line above redacts the failure, and this throw
        // used to attach the SAME raw throwable as the cause. Nothing above catches it, so the
        // JVM default handler printed the whole cause chain to stderr, which is the container
        // log, and the driver puts the JDBC URL in that chain. The cause is not attached. Its
        // sanitised text is part of the message instead, so an operator loses nothing.
        throw DatabaseUnavailableException(
            "The database did not answer inside $timeoutMs ms, after $attempt attempt(s). " +
                "Check 'database.url', the credentials, and the network. Raise " +
                "'database.startupTimeoutMs' when the database needs longer to start. " +
                "The last failure was: ${ErrorSanitizer.sanitize(lastFailure)}"
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
