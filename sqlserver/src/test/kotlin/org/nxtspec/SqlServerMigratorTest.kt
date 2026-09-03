package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-030 for SQL Server. QueueBox must create its own schema against an empty database.
 *
 * The class reuses the shared container of SqlServerTestBase but works in its own database, so
 * no other test class created its tables.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerMigratorTest {

    @Test
    fun `migrate creates the schema and the message path works`() = runBlocking {
        val databaseName = "queuebox_migrate"
        createEmptyDatabase(databaseName)

        val baseUrl = SqlServerTestBase.sqlserver.jdbcUrl
        val url = "$baseUrl;databaseName=$databaseName"
        val config = DatabaseConfig(
            type = "sqlserver",
            url = url,
            username = SqlServerTestBase.sqlserver.username,
            password = SqlServerTestBase.sqlserver.password,
            poolSize = 5
        )
        val dataSource = SqlServerDatabaseFactory.create(config)

        val applied = SqlServerMigrator().migrate(dataSource)
        assertTrue(applied >= 4, "Every bundled migration must run. Applied $applied")

        // A second run must be a no-op.
        assertEquals(0, SqlServerMigrator().migrate(dataSource))

        SqlServerDatabaseFactory.init(dataSource)

        val outbox = SqlServerOutboxRepository()
        outbox.insert(
            OutboxMessage(
                topic = "order.created",
                payload = JsonObject(mapOf("id" to JsonPrimitive(1)))
            )
        )
        val claimed = outbox.claimBatch(10)
        assertEquals(1, claimed.size)
        outbox.markSent(claimed.single().id)
        assertEquals(1L, outbox.countByState("sent"))

        SqlServerDatabaseFactory.close(dataSource)
    }

    private fun createEmptyDatabase(name: String) {
        val container = SqlServerTestBase.sqlserver
        java.sql.DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "IF DB_ID('$name') IS NOT NULL BEGIN " +
                        "ALTER DATABASE [$name] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; " +
                        "DROP DATABASE [$name]; END"
                )
                statement.execute("CREATE DATABASE [$name]")
            }
        }
    }
}
