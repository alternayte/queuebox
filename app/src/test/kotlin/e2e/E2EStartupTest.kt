package org.nxtspec.e2e

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.nxtspec.DatabaseStartup
import org.nxtspec.DatabaseUnavailableException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import kotlin.system.measureTimeMillis
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers F-056. The start waits for a database that is not up yet.
 */
class E2EStartupTest : E2ETestBase() {

    @Test
    fun `the start succeeds against a database that becomes available later`() = runBlocking {
        // A fixed host port keeps the URL stable across a restart. Testcontainers assigns a new
        // random port otherwise, so the reserved URL would point at nothing.
        val hostPort = java.net.ServerSocket(0).use { it.localPort }
        val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("queuebox_late")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
        container.portBindings = listOf("$hostPort:5432")

        val jdbcUrl = "jdbc:postgresql://localhost:$hostPort/queuebox_late"

        val dataSource = HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = "test"
                password = "test"
                maximumPoolSize = 2
                connectionTimeout = 2000
                initializationFailTimeout = -1
            }
        )

        try {
            val restart = launch(Dispatchers.IO) {
                delay(5000)
                container.start()
            }

            val elapsed = measureTimeMillis {
                withContext(Dispatchers.IO) {
                    DatabaseStartup.awaitConnection(dataSource, timeoutMs = 120000)
                }
            }
            restart.join()

            assertTrue(
                elapsed >= 4000,
                "The wait must have retried while the database was down. Took ${elapsed}ms"
            )
        } finally {
            dataSource.close()
            container.stop()
        }
    }

    @Test
    fun `the start fails with an actionable message when the database never answers`() = runBlocking {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                // Port 1 accepts no connection.
                jdbcUrl = "jdbc:postgresql://localhost:1/queuebox"
                username = "test"
                password = "test"
                maximumPoolSize = 1
                connectionTimeout = 250
                initializationFailTimeout = -1
            }
        )

        try {
            val exception = assertFailsWith<DatabaseUnavailableException> {
                withContext(Dispatchers.IO) {
                    DatabaseStartup.awaitConnection(dataSource, timeoutMs = 2000)
                }
            }

            assertTrue(exception.message!!.contains("database.startupTimeoutMs"))
        } finally {
            dataSource.close()
        }
    }
}
