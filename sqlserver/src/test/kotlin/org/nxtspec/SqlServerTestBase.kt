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
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SqlServerTestBase {

    companion object {
        // Singleton container pattern. A per-class container stops after the first test class
        // and the shared data source then points at a dead port. Ryuk removes the container
        // when the JVM exits.
        @JvmStatic
        val sqlserver: MSSQLServerContainer<*> = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
            .withPassword("StrongP@ssw0rd!")
            .acceptLicense()
            .withTmpFs(mapOf("/var/opt/mssql/data" to "rw"))
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(3))
            )
            .also { it.start() }

        // One data source for the whole JVM. A per-class data source lets the teardown of one
        // class close the pool that another class still uses.
        private var sharedDataSource: HikariDataSource? = null

        @JvmStatic
        fun sharedDataSource(): HikariDataSource = synchronized(this) {
            sharedDataSource ?: run {
                val config = DatabaseConfig(
                    type = "sqlserver",
                    url = sqlserver.jdbcUrl,
                    username = sqlserver.username,
                    password = Secret(sqlserver.password),
                    poolSize = 10
                )
                val created = SqlServerDatabaseFactory.create(config)
                SqlServerDatabaseFactory.init(created)
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
        SqlServerDatabaseFactory.init(dataSource)
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
            SchemaUtils.create(SqlServerOutboxTable, SqlServerInboxTable)
        }
    }

    private fun truncateTables() {
        transaction {
            SqlServerOutboxTable.deleteAll()
            SqlServerInboxTable.deleteAll()
        }
    }

    protected fun insertOutboxMessage(
        state: String,
        topic: String = "test-topic",
        payload: JsonElement = JsonObject(emptyMap()),
        headers: String = "{}",
        attempt: Int = 0,
        scheduledAt: Instant = Clock.System.now(),
        updatedAt: Instant = Clock.System.now()
    ): UUID {
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            SqlServerOutboxTable.insert {
                it[SqlServerOutboxTable.id] = id
                it[SqlServerOutboxTable.topic] = topic
                it[SqlServerOutboxTable.payload] = payload.toString()
                it[SqlServerOutboxTable.headers] = headers
                it[SqlServerOutboxTable.state] = state
                it[SqlServerOutboxTable.attempt] = attempt
                it[SqlServerOutboxTable.scheduledAt] = scheduledAt
                it[createdAt] = now
                it[SqlServerOutboxTable.updatedAt] = updatedAt
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
            SqlServerInboxTable.insert {
                it[SqlServerInboxTable.id] = id
                it[messageSrc] = source
                it[SqlServerInboxTable.idempotencyKey] = idempotencyKey
                it[SqlServerInboxTable.aggregateId] = aggregateId
                it[SqlServerInboxTable.payload] = payload.toString()
                it[SqlServerInboxTable.state] = state
                it[createdAt] = now
            }
        }
        return id
    }

    protected fun getOutboxMessageState(id: UUID): String {
        return transaction {
            SqlServerOutboxTable.selectAll()
                .where { SqlServerOutboxTable.id eq id }
                .single()[SqlServerOutboxTable.state]
        }
    }

    protected fun getOutboxMessageStateAndAttempt(id: UUID): Pair<String, Int> {
        return transaction {
            val row = SqlServerOutboxTable.selectAll()
                .where { SqlServerOutboxTable.id eq id }
                .single()
            row[SqlServerOutboxTable.state] to row[SqlServerOutboxTable.attempt]
        }
    }

    protected fun getOutboxStateAndScheduledAt(id: UUID): Pair<String, Instant> {
        return transaction {
            val row = SqlServerOutboxTable.selectAll()
                .where { SqlServerOutboxTable.id eq id }
                .single()
            row[SqlServerOutboxTable.state] to row[SqlServerOutboxTable.scheduledAt]
        }
    }

    protected fun getOutboxLastError(id: UUID): String? {
        return transaction {
            SqlServerOutboxTable.selectAll()
                .where { SqlServerOutboxTable.id eq id }
                .single()[SqlServerOutboxTable.lastError]
        }
    }

    protected fun getOutboxClaimedAt(id: UUID): Instant? {
        return transaction {
            SqlServerOutboxTable.selectAll()
                .where { SqlServerOutboxTable.id eq id }
                .single()[SqlServerOutboxTable.claimedAt]
        }
    }

    protected fun getInboxClaimedAt(id: UUID): Instant? {
        return transaction {
            SqlServerInboxTable.selectAll()
                .where { SqlServerInboxTable.id eq id }
                .single()[SqlServerInboxTable.claimedAt]
        }
    }

    protected fun setOutboxClaimedAt(id: UUID, claimedAt: Instant) {
        transaction {
            SqlServerOutboxTable.update({ SqlServerOutboxTable.id eq id }) {
                it[SqlServerOutboxTable.claimedAt] = claimedAt
            }
        }
    }

    protected fun setInboxClaimedAt(id: UUID, claimedAt: Instant) {
        transaction {
            SqlServerInboxTable.update({ SqlServerInboxTable.id eq id }) {
                it[SqlServerInboxTable.claimedAt] = claimedAt
            }
        }
    }

    protected fun getInboxMessageState(id: UUID): String {
        return transaction {
            SqlServerInboxTable.selectAll()
                .where { SqlServerInboxTable.id eq id }
                .single()[SqlServerInboxTable.state]
        }
    }

    protected fun getInboxProcessedAt(id: UUID): Instant? {
        return transaction {
            SqlServerInboxTable.selectAll()
                .where { SqlServerInboxTable.id eq id }
                .single()[SqlServerInboxTable.processedAt]
        }
    }
}
