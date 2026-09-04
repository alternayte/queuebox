package org.nxtspec

import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class SqlServerDatabaseFactoryTest {

    companion object {
        @Container
        @JvmStatic
        val sqlserver: MSSQLServerContainer<*> = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
            .withPassword("StrongP@ssw0rd!")
            .acceptLicense()
            .withTmpFs(mapOf("/var/opt/mssql/data" to "rw"))
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(3))
            )
    }

    @Test
    fun `create returns valid HikariDataSource`() {
        val config = DatabaseConfig(
            type = "sqlserver",
            url = sqlserver.jdbcUrl,
            username = sqlserver.username,
            password = Secret(sqlserver.password),
            poolSize = 5
        )

        val dataSource = SqlServerDatabaseFactory.create(config)

        assertNotNull(dataSource)
        assertEquals(5, dataSource.maximumPoolSize)
        assertFalse(dataSource.isClosed)

        dataSource.close()
        assertTrue(dataSource.isClosed)
    }

    @Test
    fun `init connects Exposed to SQL Server`() = runTest {
        val config = DatabaseConfig(
            type = "sqlserver",
            url = sqlserver.jdbcUrl,
            username = sqlserver.username,
            password = Secret(sqlserver.password),
            poolSize = 5
        )

        val dataSource = SqlServerDatabaseFactory.create(config)
        SqlServerDatabaseFactory.init(dataSource)

        // Verify we can execute queries through Exposed
        val result = transaction {
            exec("SELECT 1 AS test_value") { rs ->
                rs.next()
                rs.getInt("test_value")
            }
        }

        assertEquals(1, result)
        SqlServerDatabaseFactory.close(dataSource)
    }

    @Test
    fun `connection pool settings are applied correctly`() {
        val config = DatabaseConfig(
            type = "sqlserver",
            url = sqlserver.jdbcUrl,
            username = sqlserver.username,
            password = Secret(sqlserver.password),
            poolSize = 3,
            connectionTimeoutMs = 5000
        )

        val dataSource = SqlServerDatabaseFactory.create(config)

        assertEquals(3, dataSource.maximumPoolSize)
        assertEquals(5000, dataSource.connectionTimeout)
        assertEquals("TRANSACTION_READ_COMMITTED", dataSource.transactionIsolation)

        dataSource.close()
    }
}
