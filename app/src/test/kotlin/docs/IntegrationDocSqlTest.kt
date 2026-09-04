package org.nxtspec.docs

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.nxtspec.DatabaseConfig
import org.nxtspec.DatabaseFactory
import org.nxtspec.Destination
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.PostgresMigrator
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.Secret
import org.nxtspec.SqlServerDatabaseFactory
import org.nxtspec.SqlServerMigrator
import org.nxtspec.SqlServerOutboxRepository
import org.nxtspec.e2e.MockHttpServer
import org.nxtspec.http.HttpPublisher
import org.nxtspec.repository.OutboxRepositoryInterface
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.sql.Connection
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes every SQL sample in `docs/integration.md`. See F-084.
 *
 * The test parses the document and extracts every fenced block tagged `sql postgres` and
 * `sql sqlserver`. Each dialect gets a container that carries the shipped migration set. The test
 * runs every statement of every block in document order. A block that inserts an outbox row must
 * then reach an HTTP destination through the running outbox poller.
 *
 * The SQL Server container is large, so the startup timeout is generous.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationDocSqlTest {

    companion object {
        private const val DOC_PATH = "docs/integration.md"
        private const val ROUTE_PATTERN = "order.*"
        private const val DESTINATION_NAME = "doc-http"
        private const val DELIVERY_TIMEOUT_MS = 30_000L

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(System.getenv("QUEUEBOX_TEST_POSTGRES_IMAGE") ?: "postgres:16")
                .withDatabaseName("queuebox_docs")
                .withUsername("test")
                .withPassword("test")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))

        private val sqlServer: MSSQLServerContainer<*> =
            MSSQLServerContainer(
                System.getenv("QUEUEBOX_TEST_SQLSERVER_IMAGE") ?: "mcr.microsoft.com/mssql/server:2022-latest"
            )
                .withPassword("StrongP@ssw0rd!")
                .acceptLicense()
                .withTmpFs(mapOf("/var/opt/mssql/data" to "rw"))
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
    }

    private var postgresDataSource: HikariDataSource? = null
    private var sqlServerDataSource: HikariDataSource? = null
    private lateinit var postgresConnection: Connection
    private lateinit var sqlServerConnection: Connection

    @BeforeAll
    fun startInfrastructure() {
        postgres.start()
        sqlServer.start()

        val postgresCreated = DatabaseFactory.create(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                username = postgres.username,
                password = Secret(postgres.password),
                poolSize = 5
            )
        )
        postgresDataSource = postgresCreated
        PostgresMigrator().migrate(postgresCreated)
        postgresConnection = postgresCreated.connection.also { it.autoCommit = true }

        val sqlServerCreated = SqlServerDatabaseFactory.create(
            DatabaseConfig(
                type = "sqlserver",
                url = sqlServer.jdbcUrl,
                username = sqlServer.username,
                password = Secret(sqlServer.password),
                poolSize = 5
            )
        )
        sqlServerDataSource = sqlServerCreated
        SqlServerMigrator().migrate(sqlServerCreated)
        sqlServerConnection = sqlServerCreated.connection.also { it.autoCommit = true }
    }

    @AfterAll
    fun stopInfrastructure() {
        postgresConnection.close()
        sqlServerConnection.close()
        postgresDataSource?.close()
        sqlServerDataSource?.close()
        postgres.stop()
        sqlServer.stop()
    }

    @Test
    fun `the document declares a block for both dialects`() {
        val blocks = readBlocks()
        assertTrue(
            blocks.any { it.dialect == "postgres" && it.insertsOutbox() },
            "$DOC_PATH must hold a PostgreSQL block that inserts an outbox row"
        )
        assertTrue(
            blocks.any { it.dialect == "sqlserver" && it.insertsOutbox() },
            "$DOC_PATH must hold a SQL Server block that inserts an outbox row"
        )
    }

    @Test
    fun `every postgres statement runs and every inserted outbox row is delivered`() {
        DatabaseFactory.init(postgresDataSource!!)
        runDialect(
            dialect = "postgres",
            connection = postgresConnection,
            repository = OutboxRepository()
        )
    }

    @Test
    fun `every sqlserver statement runs and every inserted outbox row is delivered`() {
        SqlServerDatabaseFactory.init(sqlServerDataSource!!)
        runDialect(
            dialect = "sqlserver",
            connection = sqlServerConnection,
            repository = SqlServerOutboxRepository()
        )
    }

    /**
     * Runs every block of one dialect, then proves the delivery of every inserted outbox row.
     */
    private fun runDialect(dialect: String, connection: Connection, repository: OutboxRepositoryInterface) =
        runBlocking {
            val blocks = readBlocks().filter { it.dialect == dialect }
            assertTrue(blocks.isNotEmpty(), "$DOC_PATH holds no block tagged `sql $dialect`")

            val server = MockHttpServer()
            server.start()
            val publisher = HttpPublisher()
            val poller = startPoller(server.baseUrl, repository, publisher)

            try {
                var insertBlocks = 0
                for (block in blocks) {
                    for (statement in splitStatements(block.body)) {
                        connection.createStatement().use { it.execute(statement) }
                    }
                    if (block.insertsOutbox()) insertBlocks++
                }

                assertTrue(insertBlocks > 0, "No `$dialect` block inserted an outbox row")

                val delivered = awaitUntil(DELIVERY_TIMEOUT_MS) {
                    server.receivedRequests.size >= insertBlocks &&
                        countOutboxRows(connection, "sent") >= insertBlocks
                }
                assertTrue(
                    delivered,
                    "The poller must deliver every row that the `$dialect` blocks insert. " +
                        "Requests: ${server.receivedRequests.size}. " +
                        "Sent rows: ${countOutboxRows(connection, "sent")}. " +
                        "Pending rows: ${countOutboxRows(connection, "pending")}. " +
                        "Processing rows: ${countOutboxRows(connection, "processing")}. " +
                        "Dead rows: ${countOutboxRows(connection, "dead")}. " +
                        "Insert blocks: $insertBlocks."
                )
                assertEquals(
                    0,
                    countOutboxRows(connection, "dead"),
                    "No documented row must end in state 'dead'"
                )
            } finally {
                poller.shutdown()
                publisher.close()
                server.stop()
            }
        }

    // ==================== The document ====================

    private data class SqlBlock(val dialect: String, val body: String) {
        fun insertsOutbox(): Boolean = Regex("(?i)insert\\s+into\\s+outbox").containsMatchIn(body)
    }

    private fun readBlocks(): List<SqlBlock> {
        val file = File(repositoryRoot(), DOC_PATH)
        assertTrue(file.exists(), "$DOC_PATH does not exist")
        val blocks = mutableListOf<SqlBlock>()
        var dialect: String? = null
        val body = StringBuilder()
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (dialect == null) {
                val match = Regex("^```sql\\s+(postgres|sqlserver)$").find(trimmed)
                if (match != null) {
                    dialect = match.groupValues[1]
                    body.setLength(0)
                }
            } else if (trimmed == "```") {
                blocks.add(SqlBlock(dialect, body.toString()))
                dialect = null
            } else {
                body.append(line).append('\n')
            }
        }
        assertTrue(dialect == null, "$DOC_PATH holds an unterminated fenced block")
        assertTrue(blocks.isNotEmpty(), "$DOC_PATH holds no tagged SQL block")
        return blocks
    }

    private fun repositoryRoot(): File {
        var directory = File("").absoluteFile
        while (!File(directory, "settings.gradle.kts").exists()) {
            directory = directory.parentFile ?: error("The repository root was not found")
        }
        return directory
    }

    /**
     * Splits a block into statements on a semicolon that is outside a string literal and outside
     * a line comment.
     */
    private fun splitStatements(body: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var inComment = false
        var index = 0
        while (index < body.length) {
            val character = body[index]
            when {
                inComment -> {
                    if (character == '\n') inComment = false
                    current.append(character)
                }
                inString -> {
                    current.append(character)
                    if (character == '\'') inString = false
                }
                character == '\'' -> {
                    inString = true
                    current.append(character)
                }
                character == '-' && index + 1 < body.length && body[index + 1] == '-' -> {
                    inComment = true
                    current.append(character)
                }
                character == ';' -> {
                    statements.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        statements.add(current.toString())
        return statements.map { it.trim() }.filter { hasSql(it) }
    }

    private fun hasSql(statement: String): Boolean =
        statement.lines().any { it.isNotBlank() && !it.trim().startsWith("--") }

    // ==================== The running poller ====================

    private fun startPoller(
        baseUrl: String,
        repository: OutboxRepositoryInterface,
        publisher: HttpPublisher
    ): OutboxPoller {
        val destination = Destination.Http(
            name = DESTINATION_NAME,
            baseUrl = baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val router = MessageRouter(
            routes = listOf(RouteConfig(topicPattern = ROUTE_PATTERN, destination = DESTINATION_NAME)),
            destinations = mapOf(DESTINATION_NAME to destination)
        )
        val config = OutboxConfig(
            pollIntervalMs = 50,
            batchSize = 10,
            retryBaseDelayMs = 100,
            maxAttempts = 3
        )
        return OutboxPoller(
            config = config,
            repository = repository,
            router = router,
            publishers = listOf(publisher),
            retryStrategy = RetryStrategy(config)
        ).also { it.start() }
    }

    private fun countOutboxRows(connection: Connection, state: String): Int =
        connection.prepareStatement("SELECT count(*) FROM outbox WHERE state = ?").use { statement ->
            statement.setString(1, state)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            kotlinx.coroutines.delay(50)
        }
        return condition()
    }
}
