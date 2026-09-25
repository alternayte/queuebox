package org.nxtspec.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #66. `queuebox migrate` applies the bundled migrations with the configuration of the
 * service and exits with a code that a job runner can branch on.
 *
 * The tests call [runCommand], which `main` calls with the real arguments and passes to
 * `exitProcess`. The configuration comes from QUEUEBOX_ variables alone, as in
 * `docker run -e QUEUEBOX_DATABASE_URL=… <image> migrate`.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrateCommandTest {

    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer(System.getenv("TESTCONTAINERS_POSTGRES_IMAGE") ?: "postgres:16")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
            .also { it.start() }

    private val sqlServer: MSSQLServerContainer<*> =
        MSSQLServerContainer(
            System.getenv("TESTCONTAINERS_SQLSERVER_IMAGE") ?: "mcr.microsoft.com/mssql/server:2022-latest"
        )
            .withPassword("StrongP@ssw0rd!")
            .acceptLicense()
            .withTmpFs(mapOf("/var/opt/mssql/data" to "rw"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
            .also { it.start() }

    @AfterAll
    fun stopContainers() {
        postgres.stop()
        sqlServer.stop()
    }

    @Test
    fun `arguments that name no command exit with the usage code`() {
        listOf(
            listOf("serve"),
            listOf("migrate", "--baseline"),
            listOf("migrate", "extra"),
            listOf("migrate", "--target", "10")
        ).forEach { args ->
            assertEquals(EXIT_USAGE, runCommand(args) { emptyMap() }, "args $args")
        }
    }

    @Test
    fun `migrate applies every version, logs them and exits 0, and a second run applies nothing`() {
        val env = postgresEnv(newPostgresDatabase("cli_empty"))

        val versions = bundledVersions(POSTGRES_MIGRATIONS)
        val firstRun = captureLogs { assertEquals(EXIT_OK, runCommand(listOf("migrate"), env)) }
        assertTrue(
            firstRun.any { it.contains("Applied ${versions.size} migration(s): ${versions.joinToString(", ")}.") },
            "The command must log the versions it applied. Logged: $firstRun"
        )

        val secondRun = captureLogs { assertEquals(EXIT_OK, runCommand(listOf("migrate"), env)) }
        assertTrue(secondRun.any { it.contains("Applied 0 migration(s): none.") }, "Logged: $secondRun")
    }

    @Test
    fun `hand-applied files stop the start and migrate, and a baseline turns them into a normal install`() {
        val database = newPostgresDatabase("cli_hand_applied")
        applyByHand(postgres, database, POSTGRES_MIGRATIONS)
        val env = postgresEnv(database)

        val startup = assertFailsWith<StartupFailedException> {
            runApp(withEnv(env, "QUEUEBOX_DATABASE_MIGRATE" to "true"))
        }
        assertEquals(BASELINE_MESSAGE, startup.message)

        val refused = captureLogs { assertEquals(EXIT_FAILED, runCommand(listOf("migrate"), env)) }
        assertTrue(refused.any { it.contains(BASELINE_MESSAGE) }, "Logged: $refused")

        val baselined = captureLogs {
            assertEquals(EXIT_OK, runCommand(listOf("migrate", "--baseline", "10"), env))
        }
        val later = bundledVersions(POSTGRES_MIGRATIONS).filter { it > 10 }
        assertTrue(
            baselined.any { it.contains("Applied ${later.size} migration(s): ${later.joinToString(", ")}.") },
            "Logged: $baselined"
        )

        // A second baseline would hide the real state.
        assertEquals(EXIT_FAILED, runCommand(listOf("migrate", "--baseline", "5"), env))
        assertEquals(EXIT_OK, runCommand(listOf("migrate"), env))
    }

    @Test
    fun `migrate refuses a renamed table with a non-zero exit`() {
        val env = withEnv(
            postgresEnv(newPostgresDatabase("cli_renamed")),
            "QUEUEBOX_DATABASE_OUTBOXTABLENAME" to "my_outbox"
        )
        val logged = captureLogs { assertEquals(EXIT_FAILED, runCommand(listOf("migrate"), env)) }
        assertTrue(logged.any { it.contains("database.outboxTableName") }, "Logged: $logged")
    }

    @Test
    fun `SQL Server takes the same stop and the same baseline`() {
        val database = "cli_hand_applied"
        DriverManager.getConnection(sqlServer.jdbcUrl, sqlServer.username, sqlServer.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE [$database]") }
        }
        val url = sqlServer.jdbcUrl + ";databaseName=$database"
        applyByHand(sqlServer, url, SQLSERVER_MIGRATIONS)
        val env = {
            mapOf(
                "QUEUEBOX_DATABASE_TYPE" to "sqlserver",
                "QUEUEBOX_DATABASE_URL" to url,
                "QUEUEBOX_DATABASE_USERNAME" to sqlServer.username,
                "QUEUEBOX_DATABASE_PASSWORD" to sqlServer.password,
                "QUEUEBOX_DATABASE_MIGRATE" to "false"
            )
        }

        val refused = captureLogs { assertEquals(EXIT_FAILED, runCommand(listOf("migrate"), env)) }
        assertTrue(refused.any { it.contains(BASELINE_MESSAGE) }, "Logged: $refused")
        assertEquals(EXIT_OK, runCommand(listOf("migrate", "--baseline", "10"), env))
        assertEquals(EXIT_OK, runCommand(listOf("migrate"), env))
    }

    private fun newPostgresDatabase(name: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return postgres.jdbcUrl.substringBeforeLast('/') + "/" + name
    }

    /** `database.migrate` is false, so the command proves that it ignores the setting. */
    private fun postgresEnv(url: String): () -> Map<String, String> = {
        mapOf(
            "QUEUEBOX_DATABASE_URL" to url,
            "QUEUEBOX_DATABASE_USERNAME" to postgres.username,
            "QUEUEBOX_DATABASE_PASSWORD" to postgres.password,
            "QUEUEBOX_DATABASE_MIGRATE" to "false"
        )
    }

    private fun withEnv(env: () -> Map<String, String>, vararg extra: Pair<String, String>): () -> Map<String, String> =
        { env() + extra }

    /**
     * The migration files ship inside the provider jars on this class path, so the test reads
     * them from the source tree.
     */
    private fun migrationDirectory(location: String): File = File(
        generateSequence(File(".").absoluteFile) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile },
        location
    )

    private fun bundledVersions(location: String): List<Int> =
        migrationDirectory(location).list()!!.map { it.removePrefix("V").substringBefore("__").toInt() }.sorted()

    /** The operator applies V1 to V10 by hand, with no Flyway. */
    private fun applyByHand(container: JdbcDatabaseContainer<*>, url: String, location: String) {
        val directory = migrationDirectory(location)
        DriverManager.getConnection(url, container.username, container.password).use { connection ->
            (1..10).forEach { version ->
                val file = directory.listFiles()!!.single { it.name.startsWith("V${version}__") }
                connection.createStatement().use { it.execute(file.readText()) }
            }
        }
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("org.nxtspec.app") as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = LoggerFactory.getILoggerFactory() as LoggerContext
            start()
        }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private companion object {
        const val POSTGRES_MIGRATIONS = "postgres/src/main/resources/db/postgresql"
        const val SQLSERVER_MIGRATIONS = "sqlserver/src/main/resources/db/sqlserver"
        const val BASELINE_MESSAGE =
            "QueueBox tables exist without a migration history. Run `queuebox migrate --baseline <version>` " +
                "with the last version you applied by hand."
    }
}
