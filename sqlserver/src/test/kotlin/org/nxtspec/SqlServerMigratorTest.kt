package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.nxtspec.repository.MigrationHistoryExistsException
import org.nxtspec.repository.MigrationHistoryMissingException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        val applied = SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES)
        assertEquals(bundledVersions(), applied, "Every bundled migration must run, in order")

        // A second run must be a no-op.
        assertEquals(emptyList(), SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES))

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

    /**
     * Issue #66. A baseline at version 0 replays every file over a hand-applied schema, and a
     * guessed version can skip a file without a sign. The migration therefore stops and names
     * the baseline command, and it leaves the database as it found it.
     */
    @Test
    fun `hand-applied files without a history stop the migration`() {
        val dataSource = newDatabase("queuebox_hand_applied")
        try {
            applyByHand(dataSource, bundledVersions().filter { it.toInt() <= 10 })

            val error = assertFailsWith<MigrationHistoryMissingException> {
                SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES)
            }
            assertEquals(
                "QueueBox tables exist without a migration history. Run `queuebox migrate --baseline <version>` " +
                    "with the last version you applied by hand.",
                error.message
            )
            assertFalse(hasTable(dataSource, "flyway_schema_history"), "The stop must write no history")
            assertFalse(hasColumn(dataSource, "outbox", "sequence"), "The stop must apply no file")
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
    }

    @Test
    fun `baseline 10 records the hand-applied files and applies V11`() {
        val dataSource = newDatabase("queuebox_baseline")
        try {
            applyByHand(dataSource, bundledVersions().filter { it.toInt() <= 10 })

            assertEquals(listOf("11"), SqlServerMigrator().baseline(dataSource, "10"))
            assertTrue(hasColumn(dataSource, "outbox", "sequence"), "V11 must run after the baseline")
            assertEquals(listOf("10", "11"), historyVersions(dataSource))

            // The database is a normal one now.
            assertEquals(emptyList(), SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES))
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
    }

    @Test
    fun `baseline refuses a database that has a history and changes nothing`() {
        val dataSource = newDatabase("queuebox_baseline_refused")
        try {
            SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES)
            val before = historyVersions(dataSource)

            assertFailsWith<MigrationHistoryExistsException> {
                SqlServerMigrator().baseline(dataSource, "5")
            }
            assertEquals(before, historyVersions(dataSource))
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
    }

    @Test
    fun `baseline refuses a version that no bundled file carries`() {
        val dataSource = newDatabase("queuebox_baseline_unknown")
        try {
            listOf("99", "abc").forEach { version ->
                val error = assertFailsWith<IllegalArgumentException> {
                    SqlServerMigrator().baseline(dataSource, version)
                }
                assertTrue(error.message!!.contains("'$version'"), error.message)
            }
            assertFalse(hasTable(dataSource, "flyway_schema_history"), "A refused baseline must write no history")
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
    }

    /** QueueBox shares the application database, so an application table must not stop it. */
    @Test
    fun `a database with application tables only baselines at 0 and migrates`() {
        val dataSource = newDatabase("queuebox_app_tables")
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY)") }
                connection.commit()
            }

            assertEquals(bundledVersions(), SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES))
            assertEquals(listOf("0") + bundledVersions(), historyVersions(dataSource))
        } finally {
            SqlServerDatabaseFactory.close(dataSource)
        }
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

            val applied = SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES)
            assertTrue(applied.isNotEmpty(), "V9 must run against the already-populated database")

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

            SqlServerMigrator().migrate(dataSource, QUEUEBOX_TABLES)

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

    private fun newDatabase(name: String): com.zaxxer.hikari.HikariDataSource {
        createEmptyDatabase(name)
        return SqlServerDatabaseFactory.create(
            DatabaseConfig(
                type = "sqlserver",
                url = SqlServerTestBase.sqlserver.jdbcUrl + ";databaseName=$name",
                username = SqlServerTestBase.sqlserver.username,
                password = Secret(SqlServerTestBase.sqlserver.password),
                poolSize = 5
            )
        )
    }

    /** The versions of the bundled files, in version order. */
    private fun bundledVersions(): List<String> =
        java.io.File(requireNotNull(javaClass.getResource("/db/sqlserver")).toURI())
            .list()!!
            .map { it.removePrefix("V").substringBefore("__") }
            .sortedBy { it.toInt() }

    /** The operator applies the shipped SQL by hand, with no Flyway. */
    private fun applyByHand(dataSource: javax.sql.DataSource, versions: List<String>) {
        val directory = java.io.File(requireNotNull(javaClass.getResource("/db/sqlserver")).toURI())
        dataSource.connection.use { connection ->
            versions.forEach { version ->
                val file = directory.listFiles()!!.single { it.name.startsWith("V${version}__") }
                connection.createStatement().use { it.execute(file.readText()) }
            }
            connection.commit()
        }
    }

    private fun historyVersions(dataSource: javax.sql.DataSource): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    private fun hasTable(dataSource: javax.sql.DataSource, table: String): Boolean =
        dataSource.connection.use { connection ->
            connection.metaData.getTables(null, "dbo", table, arrayOf("TABLE")).use { it.next() }
        }

    private fun hasColumn(dataSource: javax.sql.DataSource, table: String, column: String): Boolean =
        dataSource.connection.use { connection ->
            connection.metaData.getColumns(null, "dbo", table, column).use { it.next() }
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

    private companion object {
        val QUEUEBOX_TABLES = listOf("outbox", "inbox")
    }
}
