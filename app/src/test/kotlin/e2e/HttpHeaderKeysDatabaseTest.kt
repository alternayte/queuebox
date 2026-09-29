package org.nxtspec.e2e

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.nxtspec.DatabaseConfig
import org.nxtspec.DatabaseFactory
import org.nxtspec.IdempotencyExtractor
import org.nxtspec.InboxAuthConfig
import org.nxtspec.InboxConfig
import org.nxtspec.InboxHandler
import org.nxtspec.InboxRepository
import org.nxtspec.PostgresMigrator
import org.nxtspec.Secret
import org.nxtspec.SourceConfig
import org.nxtspec.SqlServerDatabaseFactory
import org.nxtspec.SqlServerInboxRepository
import org.nxtspec.SqlServerMigrator
import org.nxtspec.configureInboxRoutes
import org.nxtspec.repository.InboxRepositoryInterface
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

/**
 * Issue #80. An HTTP source keyed on a request header deduplicates on PostgreSQL and SQL Server.
 * The source is the GitHub source of `docs/specs/header-keys.md`: the key comes from
 * `X-GitHub-Delivery`, the event type from `X-GitHub-Event`, and HMAC auth reads
 * `X-Hub-Signature-256`.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpHeaderKeysDatabaseTest {

    companion object {
        private const val SECRET = "github-webhook-secret"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(System.getenv("TESTCONTAINERS_POSTGRES_IMAGE") ?: "postgres:16")
                .withDatabaseName("queuebox_header_keys")
                .withUsername("test")
                .withPassword("test")
                .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))

        private val sqlServer: MSSQLServerContainer<*> =
            MSSQLServerContainer(
                System.getenv("TESTCONTAINERS_SQLSERVER_IMAGE") ?: "mcr.microsoft.com/mssql/server:2022-latest"
            )
                .withPassword("StrongP@ssw0rd!")
                .acceptLicense()
                .withTmpFs(mapOf("/var/opt/mssql/data" to "rw"))
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
    }

    /** One database under test: its pool, its Exposed registration and its inbox repository. */
    private class Dialect(
        val name: String,
        val dataSource: HikariDataSource,
        val database: Database,
        val init: (HikariDataSource) -> Unit,
        val repository: () -> InboxRepositoryInterface
    )

    private lateinit var dialects: List<Dialect>
    private var previousDefaultDatabase: Database? = null

    private val github = SourceConfig.Http(
        path = "/github",
        idempotencyKeyHeader = "X-GitHub-Delivery",
        idempotencyKeyPath = "$.fallback_id",
        eventTypeHeader = "X-GitHub-Event",
        aggregateIdPath = "$.repository.full_name",
        auth = InboxAuthConfig.HmacSignature(
            secret = Secret(SECRET),
            headerName = "X-Hub-Signature-256",
            signaturePrefix = "sha256="
        )
    )

    @BeforeAll
    fun startInfrastructure() {
        previousDefaultDatabase = TransactionManager.defaultDatabase
        postgres.start()
        sqlServer.start()

        val postgresPool = DatabaseFactory.create(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                username = postgres.username,
                password = Secret(postgres.password),
                poolSize = 5
            )
        )
        PostgresMigrator().migrate(postgresPool, listOf("outbox", "inbox"))

        val sqlServerPool = SqlServerDatabaseFactory.create(
            DatabaseConfig(
                type = "sqlserver",
                url = sqlServer.jdbcUrl,
                username = sqlServer.username,
                password = Secret(sqlServer.password),
                poolSize = 5
            )
        )
        SqlServerMigrator().migrate(sqlServerPool, listOf("outbox", "inbox"))

        dialects = listOf(
            Dialect("postgres", postgresPool, Database.connect(postgresPool), DatabaseFactory::init) {
                InboxRepository()
            },
            Dialect("sqlserver", sqlServerPool, Database.connect(sqlServerPool), SqlServerDatabaseFactory::init) {
                SqlServerInboxRepository()
            }
        )
    }

    @AfterAll
    fun stopInfrastructure() {
        // Restore only a default that existed. See IntegrationDocSqlTest.
        previousDefaultDatabase?.let { TransactionManager.defaultDatabase = it }
        dialects.forEach { it.dataSource.close() }
        postgres.stop()
        sqlServer.stop()
    }

    @Test
    fun `the same GitHub delivery sent twice stores one row`() = forEachDialect { dialect ->
        val body = """{"action":"opened","pull_request":{"id":7},"repository":{"full_name":"acme/api"}}"""

        val first = deliver(body, delivery = "72d3162e-cc78-11e3-81ab-4c9367dc0958", event = "pull_request")
        val second = deliver(body, delivery = "72d3162e-cc78-11e3-81ab-4c9367dc0958", event = "pull_request")

        assertEquals(HttpStatusCode.Accepted, first.status, dialect.name)
        assertEquals(HttpStatusCode.OK, second.status, dialect.name)
        assertEquals("""{"status":"duplicate"}""", second.bodyAsText(), dialect.name)
        assertEquals(
            listOf(Row("72d3162e-cc78-11e3-81ab-4c9367dc0958", "pull_request", "acme/api")),
            rows(dialect),
            dialect.name
        )
    }

    @Test
    fun `the same body with two different deliveries stores two rows`() = forEachDialect { dialect ->
        val body = """{"action":"synchronize","pull_request":{"id":7},"repository":{"full_name":"acme/api"}}"""

        val first = deliver(body, delivery = "delivery-a", event = "pull_request")
        val second = deliver(body, delivery = "delivery-b", event = "pull_request")

        assertEquals(HttpStatusCode.Accepted, first.status, dialect.name)
        assertEquals(HttpStatusCode.Accepted, second.status, dialect.name)
        assertEquals(
            listOf(
                Row("delivery-a", "pull_request", "acme/api"),
                Row("delivery-b", "pull_request", "acme/api")
            ),
            rows(dialect),
            dialect.name
        )
    }

    @Test
    fun `the key header wins, and without it the path is the fallback`() = forEachDialect { dialect ->
        val body = """{"fallback_id":"body-key","repository":{"full_name":"acme/api"}}"""

        val fromHeader = deliver(body, delivery = "header-key", event = "push")
        val fromPath = deliver(body, delivery = null, event = null)
        val repeatFromPath = deliver(body, delivery = "", event = null)

        assertEquals(HttpStatusCode.Accepted, fromHeader.status, dialect.name)
        assertEquals(HttpStatusCode.Accepted, fromPath.status, dialect.name)
        assertEquals(HttpStatusCode.OK, repeatFromPath.status, dialect.name)
        assertEquals(
            listOf(Row("body-key", null, "acme/api"), Row("header-key", "push", "acme/api")),
            rows(dialect),
            dialect.name
        )
    }

    private data class Row(val idempotencyKey: String, val eventType: String?, val aggregateId: String?)

    /** A running inbox for one dialect: the route, the handler and the dialect's repository. */
    private class Inbox(val client: io.ktor.client.HttpClient)

    private fun forEachDialect(scenario: suspend Inbox.(Dialect) -> Unit) {
        dialects.forEach { dialect ->
            dialect.init(dialect.dataSource)
            TransactionManager.defaultDatabase = dialect.database
            deleteRows(dialect)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureInboxRoutes(
                        InboxConfig(basePath = "/inbox"),
                        mapOf("github" to github),
                        InboxHandler(dialect.repository(), IdempotencyExtractor())
                    )
                }
                Inbox(client).scenario(dialect)
            }
        }
    }

    /** Posts one GitHub delivery, signed the way GitHub signs it. A null header is not sent. */
    private suspend fun Inbox.deliver(body: String, delivery: String?, event: String?): HttpResponse =
        client.post("/inbox/github") {
            contentType(ContentType.Application.Json)
            header("X-Hub-Signature-256", "sha256=" + hmacSha256(body))
            delivery?.let { header("X-GitHub-Delivery", it) }
            event?.let { header("X-GitHub-Event", it) }
            setBody(body)
        }

    private fun hmacSha256(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun deleteRows(dialect: Dialect) {
        dialect.dataSource.connection.use { connection ->
            // The pool hands out connections without auto-commit.
            connection.createStatement().use { it.execute("DELETE FROM inbox") }
            connection.commit()
        }
    }

    private fun rows(dialect: Dialect): List<Row> = dialect.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT idempotency_key, event_type, aggregate_id FROM inbox WHERE source = 'github' " +
                    "ORDER BY idempotency_key"
            ).use { result ->
                buildList {
                    while (result.next()) {
                        add(Row(result.getString(1), result.getString(2), result.getString(3)))
                    }
                }
            }
        }
    }
}
