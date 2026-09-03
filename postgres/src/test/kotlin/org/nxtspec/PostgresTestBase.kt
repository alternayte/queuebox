package org.nxtspec

import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresTestBase {

    companion object {
        // Singleton container pattern. A per-class container stops after the first test class
        // and the shared data source then points at a dead port. Ryuk removes the container
        // when the JVM exits.
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("queuebox_test")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(2))
            )
            .also { it.start() }

        // One data source for the whole JVM. A per-class data source lets the teardown of one
        // class close the pool that another class still uses.
        private var sharedDataSource: HikariDataSource? = null

        @JvmStatic
        fun sharedDataSource(): HikariDataSource = synchronized(this) {
            sharedDataSource ?: run {
                val config = DatabaseConfig(
                    url = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                    poolSize = 10
                )
                val created = DatabaseFactory.create(config)
                DatabaseFactory.init(created)
                sharedDataSource = created
                created
            }
        }
    }

    protected lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setupDatabase() {
        dataSource = sharedDataSource()
        // Another test class can register its own database as the Exposed default and then
        // close it. Re-register the shared data source for this class.
        DatabaseFactory.init(dataSource)
        createTables()
    }

    @AfterAll
    fun teardownDatabase() {
        // The shared data source stays open for the remaining test classes.
    }

    @AfterEach
    fun cleanupData() {
        truncateTables()
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

    protected fun insertOutboxMessage(
        state: String,
        topic: String = "test-topic",
        payload: JsonElement = JsonObject(emptyMap()),
        headers: JsonElement = JsonObject(emptyMap()),
        attempt: Int = 0,
        scheduledAt: Instant = Clock.System.now()
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            OutboxTable.insert {
                it[OutboxTable.id] = id
                it[OutboxTable.topic] = topic
                it[OutboxTable.payload] = payload
                it[OutboxTable.headers] = headers
                it[OutboxTable.state] = state
                it[OutboxTable.attempt] = attempt
                it[OutboxTable.scheduledAt] = scheduledAt
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return id
    }

    protected fun insertInboxMessage(
        source: String,
        idempotencyKey: String,
        payload: JsonElement = JsonObject(emptyMap()),
        state: String = "pending",
        aggregateId: String? = null
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            InboxTable.insert {
                it[InboxTable.id] = id
                it[messageSrc] = source
                it[InboxTable.idempotencyKey] = idempotencyKey
                it[InboxTable.aggregateId] = aggregateId
                it[InboxTable.payload] = payload
                it[InboxTable.state] = state
                it[createdAt] = now
            }
        }
        return id
    }

    protected fun getOutboxMessageState(id: UUID): String {
        return transaction {
            OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()[OutboxTable.state]
        }
    }

    protected fun getOutboxMessageStateAndAttempt(id: UUID): Pair<String, Int> {
        return transaction {
            val row = OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()
            row[OutboxTable.state] to row[OutboxTable.attempt]
        }
    }

    protected fun getOutboxStateAndScheduledAt(id: UUID): Pair<String, Instant> {
        return transaction {
            val row = OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()
            row[OutboxTable.state] to row[OutboxTable.scheduledAt]
        }
    }

    protected fun getOutboxLastError(id: UUID): String? {
        return transaction {
            OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()[OutboxTable.lastError]
        }
    }

    protected fun getOutboxClaimedAt(id: UUID): Instant? {
        return transaction {
            OutboxTable.selectAll()
                .where { OutboxTable.id eq id }
                .single()[OutboxTable.claimedAt]
        }
    }

    protected fun getInboxClaimedAt(id: UUID): Instant? {
        return transaction {
            InboxTable.selectAll()
                .where { InboxTable.id eq id }
                .single()[InboxTable.claimedAt]
        }
    }

    protected fun setOutboxClaimedAt(id: UUID, claimedAt: Instant) {
        transaction {
            OutboxTable.update({ OutboxTable.id eq id }) { it[OutboxTable.claimedAt] = claimedAt }
        }
    }

    protected fun setInboxClaimedAt(id: UUID, claimedAt: Instant) {
        transaction {
            InboxTable.update({ InboxTable.id eq id }) { it[InboxTable.claimedAt] = claimedAt }
        }
    }

    protected fun getInboxMessageState(id: UUID): String {
        return transaction {
            InboxTable.selectAll()
                .where { InboxTable.id eq id }
                .single()[InboxTable.state]
        }
    }

    protected fun getInboxProcessedAt(id: UUID): Instant? {
        return transaction {
            InboxTable.selectAll()
                .where { InboxTable.id eq id }
                .single()[InboxTable.processedAt]
        }
    }
}
