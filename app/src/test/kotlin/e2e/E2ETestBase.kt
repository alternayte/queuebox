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
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.nxtspec.DatabaseConfig
import org.nxtspec.DatabaseFactory
import org.nxtspec.Destination
import org.nxtspec.ExposedTransactionRunner
import org.nxtspec.InboxRelay
import org.nxtspec.InboxRelayConfig
import org.nxtspec.InboxRepository
import org.nxtspec.InboxTable
import org.nxtspec.MessageRouter
import org.nxtspec.OutboxConfig
import org.nxtspec.OutboxPoller
import org.nxtspec.OutboxRepository
import org.nxtspec.OutboxTable
import org.nxtspec.RetryStrategy
import org.nxtspec.RouteConfig
import org.nxtspec.Secret
import org.nxtspec.http.HttpPublisher
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class E2ETestBase {

    companion object {
        private const val RABBITMQ_PORT = 5672

        // Singleton container pattern. A per-class container stops after the first test class,
        // and the next class then talks to a dead port. Ryuk removes the containers when the
        // JVM exits.
        // The image comes from `TESTCONTAINERS_POSTGRES_IMAGE`, so the continuous integration
        // matrix can run the same tests against more than one PostgreSQL release. The default
        // is the release that the documentation names.
        private val postgresImage: String =
            System.getenv("TESTCONTAINERS_POSTGRES_IMAGE") ?: "postgres:16"

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(postgresImage)
            .withDatabaseName("queuebox_e2e")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(2))
            )
            .also { it.start() }

        @JvmStatic
        val rabbitMQ: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:3.12"))
            .withExposedPorts(RABBITMQ_PORT)
            .withTmpFs(mapOf("/var/lib/rabbitmq" to "rw,uid=999,gid=999"))
            .withEnv("RABBITMQ_ERLANG_COOKIE", "TESTCOOKIESTRINGLONGENOUGHFORERLANG")
            .waitingFor(
                Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2))
            )
            .also { it.start() }

        // One data source for the whole JVM.
        private var sharedDataSource: HikariDataSource? = null

        /**
         * The Exposed database of that data source.
         *
         * Exposed resolves a transaction against one global default database, and several test
         * classes in this module connect their own. A class that closes its pool can leave the
         * default pointing at it, and every later class then fails with "HikariDataSource has
         * been closed". Holding this reference lets every E2E class bind the default back to the
         * shared pool before its own tests run, whatever ran before it.
         */
        private var sharedDatabase: Database? = null

        @JvmStatic
        fun sharedDataSource(): HikariDataSource = synchronized(this) {
            sharedDataSource ?: run {
                val config = DatabaseConfig(
                    url = postgres.jdbcUrl,
                    username = postgres.username,
                    password = Secret(postgres.password),
                    poolSize = 10
                )
                val created = DatabaseFactory.create(config)
                sharedDatabase = Database.connect(created)
                sharedDataSource = created
                created
            }
        }
    }

    protected lateinit var dataSource: HikariDataSource
    protected var mockHttpServer: MockHttpServer? = null

    /**
     * The database's own idea of "now", the same expression `InboxRepository.insert` binds to
     * `scheduledAt` in production. Evaluating "now" on the database, rather than in the JVM,
     * removes the clock the two could otherwise disagree on.
     */
    private val databaseNow = object : org.jetbrains.exposed.sql.Expression<Instant>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
            queryBuilder.append("clock_timestamp()")
        }
    }

    // Every relay and poller that a helper method starts, so cleanupData can shut each one down.
    // A test that starts its own relay or poller directly, outside these helpers, still owns its
    // own shutdown.
    private val startedRelays = CopyOnWriteArrayList<InboxRelay>()
    private val startedPollers = CopyOnWriteArrayList<OutboxPoller>()

    /**
     * The Prometheus registry that `startPoller` wires its [org.nxtspec.app.MetricsCollector]
     * into, so a test can scrape a gauge that the poller updates.
     */
    protected val prometheusRegistry: io.micrometer.prometheusmetrics.PrometheusMeterRegistry =
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
            io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT
        )

    private val metricsCollector = org.nxtspec.app.MetricsCollector(prometheusRegistry)

    /**
     * Get the AMQP URL for connecting to the RabbitMQ container.
     */
    protected val amqpUrl: String
        get() = "amqp://guest:guest@${rabbitMQ.host}:${rabbitMQ.getMappedPort(RABBITMQ_PORT)}"

    @BeforeAll
    fun setupDatabase() {
        dataSource = sharedDataSource()
        // Bind the default explicitly rather than rely on what the previous class left behind.
        TransactionManager.defaultDatabase = sharedDatabase
        createTables()
    }

    @AfterAll
    fun teardownDatabase() {
        // The shared data source stays open for the remaining test classes.
    }

    /**
     * Binds the shared database before every test.
     *
     * Binding once per class is not enough. `E2EStartupTest` and `E2EShutdownTest` let the
     * application connect its own pool and then close it, which leaves the global default
     * pointing at a closed pool. The next thing to open a transaction fails with
     * "HikariDataSource has been closed", and that is usually a later test rather than the one
     * that closed the pool.
     */
    @BeforeEach
    fun bindSharedDatabase() {
        TransactionManager.defaultDatabase = sharedDatabase
    }

    @AfterEach
    fun cleanupData() = runBlocking {
        startedRelays.forEach { it.shutdown() }
        startedRelays.clear()
        startedPollers.forEach { it.shutdown() }
        startedPollers.clear()
        // The test itself may have replaced the default, so bind again before the truncate.
        TransactionManager.defaultDatabase = sharedDatabase
        truncateTables()
        mockHttpServer?.stop()
        mockHttpServer = null
    }

    private fun createTables() {
        transaction {
            SchemaUtils.create(OutboxTable, InboxTable)
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
        scheduledAt: Instant = Clock.System.now(),
        id: UUID = UUID.randomUUID()
    ): UUID {
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
    protected fun getOutboxMessageState(id: UUID): String = transaction {
        OutboxTable.selectAll()
            .where { OutboxTable.id eq id }
            .single()[OutboxTable.state]
    }

    /**
     * Get the persisted failure reason for an outbox message.
     */
    protected fun getOutboxLastError(id: UUID): String? = transaction {
        OutboxTable.selectAll()
            .where { OutboxTable.id eq id }
            .single()[OutboxTable.lastError]
    }

    /**
     * Get state and attempt count for an outbox message.
     */
    protected fun getOutboxMessageStateAndAttempt(id: UUID): Pair<String, Int> = transaction {
        val row = OutboxTable.selectAll()
            .where { OutboxTable.id eq id }
            .single()
        row[OutboxTable.state] to row[OutboxTable.attempt]
    }

    // ==================== Inbox Helpers ====================

    /**
     * Insert a message into the inbox table for testing.
     */
    protected fun insertInboxMessage(
        source: String = "test-source",
        idempotencyKey: String = UUID.randomUUID().toString(),
        payload: JsonElement = JsonObject(emptyMap()),
        state: String = "pending",
        aggregateId: String? = null,
        consumption: String = "push",
        eventType: String? = null
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            InboxTable.insert {
                it[InboxTable.id] = id
                it[messageSrc] = source
                it[InboxTable.idempotencyKey] = idempotencyKey
                it[InboxTable.aggregateId] = aggregateId
                it[InboxTable.eventType] = eventType
                it[InboxTable.payload] = payload
                it[InboxTable.state] = state
                it[InboxTable.consumption] = consumption
                // A pull claim requires `scheduled_at <= clock_timestamp()`, evaluated by the
                // database. The application clock and the container clock can differ by a few
                // milliseconds, which made a fresh row transiently ineligible for a claim called
                // immediately after insert. `databaseNow` asks the database for its own idea of
                // "now" at insert time, the same fix `InboxRepository.insert` already uses in
                // production, so there is no second clock to disagree with.
                it[InboxTable.scheduledAt] = databaseNow
                it[createdAt] = now
            }
        }
        return id
    }

    /**
     * Get an inbox message by source and idempotency key.
     */
    protected fun getInboxMessage(source: String, idempotencyKey: String): InboxRecord? = transaction {
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

    /**
     * Gives an inbox row a live lease, so `reclaimStale` leaves it alone.
     *
     * `reclaimStale` matches `state = 'processing' AND (lease_expires_at <= now OR
     * lease_expires_at IS NULL)`, and the relay runs it on its first cycle. A row planted
     * directly in `state = 'processing'` with no lease therefore reverts to `pending` almost at
     * once, which defeats a fixture that means to hold an aggregate busy for the life of a test.
     */
    protected fun renewInboxLease(source: String, idempotencyKey: String, leaseMs: Long = 30_000) {
        transaction {
            InboxTable.update({ (InboxTable.messageSrc eq source) and (InboxTable.idempotencyKey eq idempotencyKey) }) {
                it[leaseExpiresAt] = Clock.System.now().plus(kotlin.time.Duration.parse("${leaseMs}ms"))
            }
        }
    }

    /**
     * Count inbox messages for a given source.
     */
    protected fun countInboxMessages(source: String): Int = transaction {
        InboxTable.selectAll()
            .where { InboxTable.messageSrc eq source }
            .count()
            .toInt()
    }

    /**
     * Opens a transaction, inserts one outbox row inside it, and returns the row before the
     * transaction commits.
     *
     * The caller holds the transaction open across a suspension point, then calls
     * [OpenOutboxTransaction.commit]. `transaction { }` commits as soon as its block returns, so
     * this helper drives a raw JDBC connection instead, with `autoCommit` off.
     */
    protected fun openTransactionAndInsertOutbox(
        topic: String = "test-topic",
        payload: JsonElement = JsonObject(emptyMap()),
        id: UUID = UUID.randomUUID()
    ): OpenOutboxTransaction {
        val connection = dataSource.connection
        connection.autoCommit = false
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        connection.prepareStatement(
            "INSERT INTO outbox " +
                "(id, topic, payload, headers, state, attempt, max_attempts, scheduled_at, created_at, updated_at) " +
                "VALUES (?, ?, ?::jsonb, '{}'::jsonb, 'pending', 0, 3, ?, ?, ?)"
        ).use { stmt ->
            stmt.setObject(1, id)
            stmt.setString(2, topic)
            stmt.setString(3, payload.toString())
            stmt.setTimestamp(4, now)
            stmt.setTimestamp(5, now)
            stmt.setTimestamp(6, now)
            stmt.executeUpdate()
        }
        return OpenOutboxTransaction(id, connection)
    }

    /**
     * An outbox row inserted by an open, uncommitted transaction.
     */
    protected class OpenOutboxTransaction(val id: UUID, private val connection: java.sql.Connection) {
        fun commit() {
            connection.commit()
            connection.close()
        }
    }

    /**
     * Starts an [InboxRelay] against the shared repositories, and registers it for shutdown in
     * `cleanupData`.
     */
    protected fun startRelay(pollIntervalMs: Long = 20, batchSize: Int = 10): InboxRelay {
        val relay = InboxRelay(
            config = InboxRelayConfig(pollIntervalMs = pollIntervalMs, batchSize = batchSize),
            inboxRepository = InboxRepository(),
            outboxRepository = OutboxRepository(),
            transactionRunner = ExposedTransactionRunner()
        )
        relay.start()
        startedRelays.add(relay)
        return relay
    }

    /**
     * Starts an [OutboxPoller] that routes every topic to the running [mockHttpServer], and
     * registers it for shutdown in `cleanupData`.
     */
    protected fun startPoller(pollIntervalMs: Long = 20, batchSize: Int = 10): OutboxPoller {
        val server = checkNotNull(mockHttpServer) { "call startMockHttpServer before startPoller" }
        val destination = Destination.Http(
            name = "test-http",
            baseUrl = server.baseUrl,
            path = "/webhook",
            timeoutMs = 5000
        )
        val config = OutboxConfig(pollIntervalMs = pollIntervalMs, batchSize = batchSize, maxAttempts = 3)
        val poller = OutboxPoller(
            config = config,
            repository = OutboxRepository(),
            router = MessageRouter(
                routes = listOf(RouteConfig(topicPattern = "**", destination = "test-http")),
                destinations = mapOf("test-http" to destination)
            ),
            publishers = listOf(HttpPublisher()),
            retryStrategy = RetryStrategy(config),
            metricsCollector = metricsCollector
        )
        poller.start()
        startedPollers.add(poller)
        return poller
    }

    /**
     * Reads one metric value out of the Prometheus exposition text that
     * [prometheusRegistry] scrapes.
     *
     * The exposition format is one `name value` line per series, with an optional `{tags}`
     * block between the name and the value. `queuebox_outbox_oldest_pending_age_seconds`
     * carries no tags, so a plain prefix match is enough.
     *
     * @return the scraped value, or 0.0 when the metric has not registered a sample yet.
     */
    protected fun scrapeMetric(name: String): Double {
        val line = prometheusRegistry.scrape()
            .lineSequence()
            .firstOrNull { !it.startsWith("#") && (it == name || it.startsWith("$name ") || it.startsWith("$name{")) }
            ?: return 0.0
        return line.substringAfterLast(' ').toDouble()
    }

    /**
     * Waits until `state = 'processed'` rows of one aggregate reach `count`.
     *
     * Waiting for every row to finish is not itself proof of an exclusivity rule: a test that
     * needs to prove "at most one in flight" must not rely on a sampled peak, since a sampler
     * that misses the (often sub-millisecond) 'processing' window returns a value
     * indistinguishable from a genuine pass. Prove that kind of claim by calling the claim
     * directly, twice, with no mark-processed step between the two calls, and asserting on what
     * the second call returns (see `OrderingGuaranteeTest`). This helper is for waiting on the
     * end state of a batch that is expected to complete, not for proving exclusivity.
     */
    protected suspend fun awaitProcessedCount(aggregateId: String, count: Int, timeoutMs: Long = 15000): Boolean =
        awaitUntil(timeoutMs = timeoutMs) {
            transaction {
                InboxTable.selectAll()
                    .where { (InboxTable.aggregateId eq aggregateId) and (InboxTable.state eq "processed") }
                    .count()
                    .toInt()
            } >= count
        }

    /**
     * Reads the `x-idempotency-key` header of every outbox row, in the order that
     * `created_at` gives, which is the order the relay forwarded the messages.
     */
    protected fun forwardedIdempotencyKeysInOrder(): List<String> = transaction {
        OutboxTable.selectAll()
            .orderBy(OutboxTable.createdAt to org.jetbrains.exposed.sql.SortOrder.ASC)
            .mapNotNull { row ->
                (row[OutboxTable.headers] as? JsonObject)
                    ?.get("x-idempotency-key")
                    ?.toString()
                    ?.trim('"')
            }
    }

    /**
     * Reads the `n` field of a JSON body received by the mock HTTP server, so a test can name
     * which outbox row a request delivered.
     */
    protected fun bodyName(body: String): String = kotlinx.serialization.json.Json.parseToJsonElement(body)
        .jsonObject["n"]
        .toString()
        .trim('"')

    // ==================== Mock HTTP Server ====================

    /**
     * Start a mock HTTP server for testing HTTP destinations.
     */
    protected fun startMockHttpServer(
        responseCode: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = """{"status": "ok"}""",
        echoRequestHeaders: Boolean = false
    ): MockHttpServer {
        val server = MockHttpServer(responseCode, responseBody, echoRequestHeaders)
        server.start()
        mockHttpServer = server
        return server
    }

    /**
     * Waits until the condition holds, or until the timeout expires.
     *
     * A `repeat` loop with `return@repeat` continues the loop instead of leaving it, so the
     * tests use this helper.
     *
     * @return true when the condition held before the timeout
     */
    protected suspend fun awaitUntil(timeoutMs: Long = 15000, pollMs: Long = 50, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            kotlinx.coroutines.delay(pollMs)
        }
        return condition()
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
    private var responseBody: String = """{"status": "ok"}""",
    private val echoRequestHeaders: Boolean = false
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val _receivedRequests = CopyOnWriteArrayList<ReceivedRequest>()

    val receivedRequests: List<ReceivedRequest>
        get() = _receivedRequests.toList()

    /** The number of requests received so far. */
    val requestCount: Int
        get() = _receivedRequests.size

    /** The body of every request received so far, in the order received. */
    val receivedBodies: List<String>
        get() = _receivedRequests.map { it.body }

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

                    val responseText = if (echoRequestHeaders) {
                        // The error body repeats the request headers, which is what a badly
                        // behaved destination does. F-016 requires the persisted error to
                        // redact the secret that such a body carries.
                        """{"error":"boom","received":"$headers"}"""
                    } else {
                        responseBody
                    }
                    call.respondText(responseText, ContentType.Application.Json, responseCode)
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

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }

    data class ReceivedRequest(val path: String, val body: String, val headers: Map<String, String>)
}
