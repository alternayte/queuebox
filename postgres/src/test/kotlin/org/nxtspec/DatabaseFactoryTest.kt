package org.nxtspec

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
class DatabaseFactoryTest : PostgresTestBase() {

    @Test
    fun `create should return HikariDataSource with correct pool settings`() {
        val testConfig = DatabaseConfig(
            url = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
            poolSize = 5
        )
        val testDataSource = DatabaseFactory.create(testConfig)
        try {
            assertEquals(5, testDataSource.maximumPoolSize)
            assertEquals(postgres.jdbcUrl, testDataSource.jdbcUrl)
            assertFalse(testDataSource.isAutoCommit)
            assertEquals("TRANSACTION_READ_COMMITTED", testDataSource.transactionIsolation)
        } finally {
            DatabaseFactory.close(testDataSource)
        }
    }

    @Test
    fun `init should connect Exposed when DataSource valid`() {
        transaction {
            exec("SELECT 1")
        }
    }

    @Test
    fun `create should set connection timeout from config`() {
        val customConfig = DatabaseConfig(
            url = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
            poolSize = 3,
            connectionTimeoutMs = 15000
        )
        val customDataSource = DatabaseFactory.create(customConfig)
        try {
            assertEquals(15000, customDataSource.connectionTimeout)
        } finally {
            DatabaseFactory.close(customDataSource)
        }
    }

    @Test
    fun `close should shutdown pool when called`() {
        val tempConfig = DatabaseConfig(
            url = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
            poolSize = 2
        )
        val tempDataSource = DatabaseFactory.create(tempConfig)
        assertFalse(tempDataSource.isClosed)

        DatabaseFactory.close(tempDataSource)

        assertTrue(tempDataSource.isClosed)
    }

    @Test
    fun `connection should execute query after init`() {
        transaction {
            exec("CREATE TABLE IF NOT EXISTS temp_test (id SERIAL PRIMARY KEY, value TEXT)")
            exec("INSERT INTO temp_test (value) VALUES ('test-value')")
            val result = exec("SELECT value FROM temp_test WHERE value = 'test-value'") { rs ->
                rs.next()
                rs.getString("value")
            }
            assertEquals("test-value", result)
            exec("DROP TABLE temp_test")
        }
    }
}
