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
}
