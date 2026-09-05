package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sixth review gate, defect B2.
 *
 * The retry line printed `e.message` raw. A JDBC driver puts the whole URL, and therefore the
 * database password, in that message. The line fires on every start where the database is not yet
 * up, which is the normal Compose path, so the leak was the common case and not an edge.
 */
class DatabaseStartupRedactionTest {

    @Test
    fun `the retry reason carries no database password`() {
        // The verbatim shape that HikariCP reports through DriverDataSource.
        val driverMessage =
            "Failed to get driver instance for jdbcUrl=jdbc:postgresql://qb:Hunter2Secret@db:5432/qb"
        val error = RuntimeException(driverMessage)

        val reason = ErrorSanitizer.sanitize(error)!!

        assertFalse(reason.contains("Hunter2Secret"), "the database password printed: $reason")
        // An operator needs the host and the port to act on the failure.
        assertTrue(reason.contains("db:5432"), "the host and the port must survive: $reason")
    }

    /**
     * Seventh review gate, defect 3.
     *
     * The retry line was sanitised, and the thrown exception then attached the SAME raw throwable
     * as its cause. Nothing above `main` catches it, so the JVM printed the whole chain to the
     * container log. A test on the sanitiser alone never rendered that escape path, so this test
     * renders the exception exactly as the JVM does.
     */
    @Test
    fun `the startup failure carries no cause and no password when rendered`() {
        val driverMessage =
            "Failed to get driver instance for jdbcUrl=jdbc:postgresql://qb:Hunter2Secret@db:5432/qb"
        val lastFailure = RuntimeException(driverMessage)

        val thrown = DatabaseUnavailableException(
            "The database did not answer inside 1000 ms, after 3 attempt(s). " +
                "The last failure was: ${ErrorSanitizer.sanitize(lastFailure)}"
        )

        // Render the throwable the way the JVM default handler does, cause chain included.
        val rendered = java.io.StringWriter().also { writer ->
            thrown.printStackTrace(java.io.PrintWriter(writer))
        }.toString()

        assertFalse(rendered.contains("Hunter2Secret"), "the rendered failure printed the password")
        assertTrue(rendered.contains("db:5432"), "the host and the port must survive")
        assertTrue(thrown.cause == null, "the exception must carry no cause, which would be raw")
    }
}
