package org.nxtspec

import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers F-056. The start waits for the database rather than exiting at once.
 */
class DatabaseStartupTest {

    /** Fails the first [failures] attempts, then succeeds. */
    private class FlakyDataSource(private val failures: Int) : DataSource by NoDataSource {
        var attempts = 0

        override fun getConnection(): Connection {
            attempts++
            if (attempts <= failures) throw java.sql.SQLException("the database is starting")
            return FakeConnection
        }
    }

    private object FakeConnection : Connection by NoConnection {
        override fun isValid(timeout: Int): Boolean = true
        override fun close() = Unit
    }

    @Test
    fun `returns at once when the first attempt succeeds`() {
        val dataSource = FlakyDataSource(failures = 0)
        val slept = mutableListOf<Long>()

        DatabaseStartup.awaitConnection(dataSource, timeoutMs = 60000, sleep = { slept.add(it) })

        assertEquals(1, dataSource.attempts)
        assertTrue(slept.isEmpty(), "A first success must not sleep")
    }

    @Test
    fun `retries with backoff until the database answers`() {
        val dataSource = FlakyDataSource(failures = 3)
        val slept = mutableListOf<Long>()
        var clock = 0L

        DatabaseStartup.awaitConnection(
            dataSource,
            timeoutMs = 60000,
            sleep = {
                slept.add(it)
                clock += it
            },
            now = { clock }
        )

        assertEquals(4, dataSource.attempts)
        assertEquals(listOf(500L, 1000L, 2000L), slept, "The delay must double")
    }

    @Test
    fun `fails with an actionable message after the timeout`() {
        val dataSource = FlakyDataSource(failures = Int.MAX_VALUE)
        var clock = 0L

        val exception = assertFailsWith<DatabaseUnavailableException> {
            DatabaseStartup.awaitConnection(
                dataSource,
                timeoutMs = 3000,
                sleep = { clock += it },
                now = { clock }
            )
        }

        assertContains(exception.message!!, "3000 ms")
        assertContains(exception.message!!, "database.startupTimeoutMs")
        // Seventh review gate, defect 3. The exception carries NO cause. A driver puts the JDBC
        // URL, and therefore the password, in the message of the failure that caused it. Nothing
        // above `main` catches this, so the JVM printed the whole chain to the container log. The
        // sanitised text of the last failure is part of the message instead.
        assertNull(exception.cause, "the exception must carry no cause, which would be raw")
        assertContains(exception.message!!, "The last failure was:")
    }

    @Test
    fun `never sleeps past the deadline`() {
        val dataSource = FlakyDataSource(failures = Int.MAX_VALUE)
        val slept = mutableListOf<Long>()
        var clock = 0L

        assertFailsWith<DatabaseUnavailableException> {
            DatabaseStartup.awaitConnection(
                dataSource,
                timeoutMs = 700,
                sleep = {
                    slept.add(it)
                    clock += it
                },
                now = { clock }
            )
        }

        assertEquals(700L, slept.sum(), "The waits must add up to the budget, never more")
    }
}
