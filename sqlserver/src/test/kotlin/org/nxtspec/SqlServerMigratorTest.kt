package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.Flyway
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
            password = Secret(SqlServerTestBase.sqlserver.password),
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
        outbox.markSent(claimed.single().id, claimed.single().claimToken)
        assertEquals(1L, outbox.countByState("sent"))

        SqlServerDatabaseFactory.close(dataSource)
    }

    @Test
    fun `migrate succeeds against a schema that an operator already created`() = runBlocking {
        // Every migration file must be safe to run twice, because an operator can apply the
        // SQL by hand before Flyway baselines the database.
        val databaseName = "queuebox_existing"
        createEmptyDatabase(databaseName)

        val url = SqlServerTestBase.sqlserver.jdbcUrl + ";databaseName=$databaseName"
        val config = DatabaseConfig(
            type = "sqlserver",
            url = url,
            username = SqlServerTestBase.sqlserver.username,
            password = Secret(SqlServerTestBase.sqlserver.password),
            poolSize = 5
        )
        val dataSource = SqlServerDatabaseFactory.create(config)

        dataSource.connection.use { connection ->
            listOf(
                "V1__create_outbox.sql",
                "V2__create_inbox.sql",
                "V3__add_claimed_at.sql",
                "V4__add_last_error.sql"
            ).forEach { name ->
                val sql = requireNotNull(
                    javaClass.getResourceAsStream("/db/sqlserver/$name")
                ) { "Migration $name must be on the classpath" }.bufferedReader().readText()
                connection.createStatement().use { it.execute(sql) }
            }
        }

        val applied = SqlServerMigrator().migrate(dataSource)
        assertTrue(applied >= 4, "Flyway records every file. Applied $applied")

        SqlServerDatabaseFactory.close(dataSource)
    }

    /**
     * Covers F-090's DoD requirement: the migration must apply to a database that already holds
     * rows, not only to an empty one.
     */
    @Test
    fun `V9 applies to a populated outbox table`() = runBlocking {
        val databaseName = "queuebox_v9_populated"
        createEmptyDatabase(databaseName)

        val url = SqlServerTestBase.sqlserver.jdbcUrl + ";databaseName=$databaseName"
        val config = DatabaseConfig(
            type = "sqlserver",
            url = url,
            username = SqlServerTestBase.sqlserver.username,
            password = Secret(SqlServerTestBase.sqlserver.password),
            poolSize = 5
        )
        val dataSource = SqlServerDatabaseFactory.create(config)
        try {
            // Migrate only up to V8, so the row insert below happens against the schema that
            // predates this feature.
            Flyway.configure()
                .dataSource(dataSource)
                .locations(SqlServerMigrator.LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("8")
                .load()
                .migrate()

            val rowCount = 5
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO outbox (id, topic, payload, state, attempt, max_attempts, " +
                        "scheduled_at, created_at, updated_at) " +
                        "VALUES (NEWID(), ?, '{}', 'pending', 0, 5, SYSUTCDATETIME(), SYSUTCDATETIME(), SYSUTCDATETIME())"
                ).use { stmt ->
                    repeat(rowCount) {
                        stmt.setString(1, "order.created")
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                connection.commit()
            }

            val applied = SqlServerMigrator().migrate(dataSource)
            assertTrue(applied >= 1, "V9 must run against the already-populated database")

            dataSource.connection.use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM outbox").use { rs ->
                        rs.next()
                        assertEquals(rowCount, rs.getInt(1), "Every pre-existing row must survive the migration")
                    }
                    stmt.executeQuery("SELECT COUNT(*) FROM outbox WHERE aggregate_type IS NOT NULL").use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1), "Every pre-existing row must have a null aggregate_type")
                    }
                }
            }
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
    }

    /**
     * V11 numbers the rows that exist before the upgrade in created_at order, new rows take
     * higher values, and a second run of the file changes nothing. See
     * docs/specs/outbox-key-order.md.
     */
    @Test
    fun `V11 numbers existing outbox rows in created_at order`() = runBlocking {
        val databaseName = "queuebox_v11_populated"
        createEmptyDatabase(databaseName)
        val config = DatabaseConfig(
            type = "sqlserver",
            url = SqlServerTestBase.sqlserver.jdbcUrl + ";databaseName=$databaseName",
            username = SqlServerTestBase.sqlserver.username,
            password = Secret(SqlServerTestBase.sqlserver.password),
            poolSize = 5
        )
        val dataSource = SqlServerDatabaseFactory.create(config)
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(SqlServerMigrator.LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("10")
                .load()
                .migrate()

            // Inserted out of created_at order, so the backfill must sort rather than keep the
            // physical order.
            val topicsByAge = listOf("second" to 2, "first" to 1, "third" to 3)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO outbox (id, topic, payload, state, scheduled_at, created_at, updated_at) " +
                        "VALUES (NEWID(), ?, '{}', 'pending', SYSUTCDATETIME(), ?, SYSUTCDATETIME())"
                ).use { stmt ->
                    topicsByAge.forEach { (topic, minute) ->
                        stmt.setString(1, topic)
                        stmt.setTimestamp(2, java.sql.Timestamp.valueOf("2026-01-01 00:0$minute:00"))
                        stmt.executeUpdate()
                    }
                }
                connection.commit()
            }

            SqlServerMigrator().migrate(dataSource)

            fun sequenceByTopic(): Map<String, Long> = dataSource.connection.use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT topic, sequence FROM outbox").use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
                    }
                }
            }
            val backfilled = sequenceByTopic()
            assertTrue(backfilled.getValue("first") < backfilled.getValue("second"))
            assertTrue(backfilled.getValue("second") < backfilled.getValue("third"))

            // A second run of the file must keep every number.
            val sql = requireNotNull(javaClass.getResourceAsStream("/db/sqlserver/V11__add_outbox_sequence.sql"))
                .bufferedReader().readText()
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute(sql) }
                connection.commit()
            }
            assertEquals(backfilled, sequenceByTopic())

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO outbox (id, topic, payload, state, scheduled_at, created_at, updated_at) " +
                        "VALUES (NEWID(), ?, '{}', 'pending', SYSUTCDATETIME(), ?, SYSUTCDATETIME())"
                ).use { stmt ->
                    stmt.setString(1, "new")
                    stmt.setTimestamp(2, java.sql.Timestamp.valueOf("2020-01-01 00:00:00"))
                    stmt.executeUpdate()
                }
                connection.commit()
            }
            assertTrue(sequenceByTopic().getValue("new") > backfilled.values.max())
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
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
