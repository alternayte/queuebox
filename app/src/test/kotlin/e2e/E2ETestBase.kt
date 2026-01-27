package org.nxtspec.e2e

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.nxtspec.DatabaseConfig
import org.nxtspec.DatabaseFactory
import org.nxtspec.InboxTable
import org.nxtspec.OutboxTable
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Base class for E2E tests providing shared TestContainers infrastructure.
 *
 * Provides:
 * - PostgreSQL container for database operations
 * - RabbitMQ container for message queue operations
 * - MockHttpServer for testing HTTP destinations
 * - Helper methods for inserting and querying test data
 */
@Tag("e2e")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class E2ETestBase {

    companion object {
        private const val RABBITMQ_PORT = 5672

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("queuebox_e2e")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(2))
            )

        @Container
        @JvmStatic
        val rabbitMQ: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
            .withExposedPorts(RABBITMQ_PORT)
            .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
            .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
            .waitingFor(
                Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2))
            )
    }

    protected lateinit var dataSource: HikariDataSource
    protected var mockHttpServer: MockHttpServer? = null

    /**
     * Get the AMQP URL for connecting to the RabbitMQ container.
     */
    protected val amqpUrl: String
        get() = "amqp://guest:guest@${rabbitMQ.host}:${rabbitMQ.getMappedPort(RABBITMQ_PORT)}"

    @BeforeAll
    fun setupDatabase() {
        val config = DatabaseConfig(
            url = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
            poolSize = 10
        )
        dataSource = DatabaseFactory.create(config)
        DatabaseFactory.init(dataSource)
        createTables()
    }

    @AfterAll
    fun teardownDatabase() {
        dropTables()
        DatabaseFactory.close(dataSource)
    }

    @AfterEach
    fun cleanupData() {
        truncateTables()
        mockHttpServer?.stop()
        mockHttpServer = null
    }

    private fun createTables() {
        transaction {
            SchemaUtils.create(OutboxTable, InboxTable)
        }
    }

    private fun dropTables() {
        transaction {
            SchemaUtils.drop(OutboxTable, InboxTable)
        }
    }

    private fun truncateTables() {
        transaction {
            OutboxTable.deleteAll()
            InboxTable.deleteAll()
        }
    }

    // ==================== Outbox Helpers ====================

    /**
     * Insert a message into the outbox table for testing.
     */
    protected fun insertOutboxMessage(
        topic: String = "test-topic",
        payload: JsonElement = JsonObject(emptyMap()),
        state: String = "pending",
        attempt: Int = 0,
        maxAttempts: Int = 3,
        scheduledAt: Instant = Clock.System.now()
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            OutboxTable.insert {
                it[OutboxTable.id] = id
                it[OutboxTable.topic] = topic
                it[OutboxTable.payload] = payload
                it[OutboxTable.state] = state
                it[OutboxTable.attempt] = attempt
                it[OutboxTable.maxAttempts] = maxAttempts
                it[OutboxTable.scheduledAt] = scheduledAt
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return id
    }

    /**
     * Get the current state of an outbox message.
     */
    protected fun getOutboxMessageState(id: UUID): String {
        return transaction {
            OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()[OutboxTable.state]
        }
    }

    /**
     * Get state and attempt count for an outbox message.
     */
    protected fun getOutboxMessageStateAndAttempt(id: UUID): Pair<String, Int> {
        return transaction {
            val row = OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()
            row[OutboxTable.state] to row[OutboxTable.attempt]
        }
    }

    // ==================== Inbox Helpers ====================

    /**
     * Insert a message into the inbox table for testing.
     */
    protected fun insertInboxMessage(
        source: String = "test-source",
        idempotencyKey: String = UUID.randomUUID().toString(),
        payload: JsonElement = JsonObject(emptyMap()),
        state: String = "pending"
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            InboxTable.insert {
                it[InboxTable.id] = id
                it[messageSrc] = source
                it[InboxTable.idempotencyKey] = idempotencyKey
                it[InboxTable.payload] = payload
                it[InboxTable.state] = state
                it[createdAt] = now
            }
        }
        return id
    }

    /**
     * Get an inbox message by source and idempotency key.
     */
    protected fun getInboxMessage(source: String, idempotencyKey: String): InboxRecord? {
        return transaction {
            InboxTable.selectAll()
                .where { (InboxTable.messageSrc eq source) and (InboxTable.idempotencyKey eq idempotencyKey) }
                .singleOrNull()
                ?.let { row ->
                    InboxRecord(
                        id = row[InboxTable.id].value,
                        source = row[InboxTable.messageSrc],
                        idempotencyKey = row[InboxTable.idempotencyKey],
                        payload = row[InboxTable.payload],
                        state = row[InboxTable.state],
                        createdAt = row[InboxTable.createdAt]
                    )
                }
        }
    }

    /**
     * Count inbox messages for a given source.
     */
    protected fun countInboxMessages(source: String): Int {
        return transaction {
            InboxTable.selectAll()
                .where { InboxTable.messageSrc eq source }
                .count()
                .toInt()
        }
    }

    // ==================== Mock HTTP Server ====================

    /**
     * Start a mock HTTP server for testing HTTP destinations.
     */
    protected fun startMockHttpServer(
        responseCode: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = """{"status": "ok"}"""
    ): MockHttpServer {
        val server = MockHttpServer(responseCode, responseBody)
        server.start()
        mockHttpServer = server
        return server
    }

    /**
     * Simple record class for inbox query results.
     */
    data class InboxRecord(
        val id: UUID,
        val source: String,
        val idempotencyKey: String,
        val payload: JsonElement,
        val state: String,
        val createdAt: Instant
    )
}

/**
 * Mock HTTP server for testing HTTP destination delivery.
 * Tracks all received requests for verification.
 */
class MockHttpServer(
    private var responseCode: HttpStatusCode = HttpStatusCode.OK,
    private var responseBody: String = """{"status": "ok"}"""
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val _receivedRequests = CopyOnWriteArrayList<ReceivedRequest>()

    val receivedRequests: List<ReceivedRequest>
        get() = _receivedRequests.toList()

    var port: Int = 0
        private set

    val baseUrl: String
        get() = "http://localhost:$port"

    fun start() {
        port = findAvailablePort()
        server = embeddedServer(Netty, port = port) {
            routing {
                post("{...}") {
                    val body = call.receiveText()
                    val headers = call.request.headers.entries()
                        .associate { it.key to it.value.joinToString(",") }

                    _receivedRequests.add(
                        ReceivedRequest(
                            path = call.request.uri,
                            body = body,
                            headers = headers
                        )
                    )

                    call.respondText(responseBody, ContentType.Application.Json, responseCode)
                }
            }
        }.start(wait = false)

        // Wait for server to be ready
        Thread.sleep(100)
    }

    fun stop() {
        server?.stop(100, 100)
        server = null
        _receivedRequests.clear()
    }

    /**
     * Configure response for subsequent requests.
     */
    fun setResponse(code: HttpStatusCode, body: String = "") {
        responseCode = code
        responseBody = body
    }

    /**
     * Clear received requests.
     */
    fun clearRequests() {
        _receivedRequests.clear()
    }

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    data class ReceivedRequest(
        val path: String,
        val body: String,
        val headers: Map<String, String>
    )
}
