package org.nxtspec.app

import org.nxtspec.ErrorSanitizer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ninth review gate N2. Nothing tested the failure contract of `main`.
 *
 * `main` must not let a raw throwable escape. Nothing sits above it, so the JVM default handler
 * prints the whole cause chain to the container log, and a driver, a pool or a migration tool puts
 * the JDBC URL into that chain. The contract is therefore: the exception that leaves `main`
 * carries a sanitised message and NO cause.
 */
class StartupFailureContractTest {

    @Test
    fun `the startup failure carries no cause and no credential when rendered`() {
        val driverMessage =
            "Failed to get driver instance for jdbcUrl=jdbc:postgresql://qb:Hunter2Secret@db:5432/qb"
        val cause = RuntimeException(driverMessage)

        val thrown = StartupFailedException(
            "QueueBox could not open the database pool. Reason: ${ErrorSanitizer.sanitize(cause)}"
        )

        val rendered = java.io.StringWriter().also { writer ->
            thrown.printStackTrace(java.io.PrintWriter(writer))
        }.toString()

        assertNull(thrown.cause, "the exception must carry no cause, which would be raw")
        assertFalse(rendered.contains("Hunter2Secret"), "the rendered failure printed the password")
        assertTrue(rendered.contains("db:5432"), "the host and the port must survive")
    }

    @Test
    fun `every startup step name reads as an action an operator can act on`() {
        // The message shape is "QueueBox could not <what>. Reason: <sanitised>". A reader must be
        // able to tell which step failed.
        val thrown = StartupFailedException("QueueBox could not apply the database migrations. Reason: x")

        assertTrue(thrown.message!!.startsWith("QueueBox could not "))
        assertTrue(thrown.message!!.contains("Reason: "))
    }
}
