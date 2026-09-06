package org.nxtspec.capture

import com.microsoft.sqlserver.jdbc.SQLServerDataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.nxtspec.*
import org.nxtspec.repository.OutboxRepositoryInterface
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.*

@Tag("integration")
class CaptureIntegrationTest {

    private companion object {
        /**
         * How long a delivery may take before the test gives up.
         *
         * The reconciliation timer of `exercise` is five minutes, so any delivery inside this
         * bound came from a capture wake rather than from reconciliation. The bound is generous
         * because the PostgreSQL connector sometimes needs a long time to resume streaming on a
         * machine that is already running several database containers, and a tighter bound made
         * this test fail for the load of its neighbours rather than for its own behaviour.
         */
        const val DELIVERY_TIMEOUT_MS = 120_000L

        /** How long a state change may take to become visible after its delivery. */
        const val STATE_TIMEOUT_MS = 30_000L
    }

    @Test fun `PostgreSQL logical capture wakes delivery and survives restart`() = runBlocking {
        PostgreSQLContainer("postgres:16").withCommand("postgres", "-c", "wal_level=logical").use { db ->
            db.start()
            val config = DatabaseConfig(url = db.jdbcUrl, username = db.username, password = Secret(db.password))
            val source = PGSimpleDataSource().apply {
                setURL(db.jdbcUrl)
                user = db.username
                setPassword(db.password)
            }
            PostgresMigrator().migrate(source)
            source.connection.use {
                it.createStatement().use { stmt -> stmt.execute("CREATE PUBLICATION queuebox_outbox FOR TABLE outbox") }
            }
            DatabaseFactory.init(source)
            exercise(config, source, OutboxRepository())
        }
    }

    @Test fun `SQL Server CDC wakes delivery and survives restart`() = runBlocking {
        MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense().withEnv("MSSQL_AGENT_ENABLED", "true").use { db ->
                db.start()
                val source = SQLServerDataSource().apply {
                    setURL(db.jdbcUrl)
                    user = db.username
                    setPassword(db.password)
                }
                source.connection.use { it.createStatement().use { stmt -> stmt.execute("CREATE DATABASE queuebox") } }
                source.databaseName = "queuebox"
                val config =
                    DatabaseConfig(
                        type = "sqlserver",
                        url = db.jdbcUrl + ";databaseName=queuebox",
                        username = db.username,
                        password = Secret(db.password)
                    )
                SqlServerMigrator().migrate(source)
                source.connection.use {
                    it.createStatement().use { stmt ->
                        stmt.execute("EXEC sys.sp_cdc_enable_db")
                        stmt.execute(
                            "EXEC sys.sp_cdc_enable_table @source_schema=N'dbo', @source_name=N'outbox', @role_name=NULL, @supports_net_changes=0"
                        )
                    }
                }
                SqlServerDatabaseFactory.init(source)
                exercise(config, source, SqlServerOutboxRepository())
            }
    }

    private suspend fun exercise(database: DatabaseConfig, source: DataSource, repository: OutboxRepositoryInterface) {
        val directory = Files.createTempDirectory("queuebox-capture-test-")
        val config = CaptureConfig(
            mode = if (database.type == "postgresql") "postgres-logical" else "sqlserver-cdc",
            enabled = true,
            identity = "capture_test",
            stateDirectory = directory.toString(),
            // Five minutes. The assertions below allow one minute, so a delivery inside that
            // minute cannot have come from reconciliation, whatever the load on the machine.
            // A tighter timer would make the proof depend on how busy the runner is, which is
            // how this test failed on a loaded workstation and on a two-core runner.
            reconciliationIntervalMs = 300000,
            connection = CaptureConnection(trustServerCertificate = true)
        )
        val signal = DeliverySignal()
        var capture = OutboxCapture(database, config, source, signal)
        val received = kotlinx.coroutines.channels.Channel<OutboxMessage>(16)
        val failOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()
        val publisher = object : Publisher {
            override fun supports(destination: Destination) = true
            override suspend fun publish(
                message: OutboxMessage,
                destination: Destination,
                context: PublishContext
            ): Result<Unit> {
                if (failOnce.remove(message.id)) {
                    return Result.failure(IllegalStateException("the receiver refused the first attempt"))
                }
                received.send(message)
                return Result.success(Unit)
            }
        }
        val delivery = OutboxConfig(capture = config, batchSize = 4, concurrency = 2)
        val poller = OutboxPoller(
            delivery,
            repository,
            MessageRouter(
                listOf(RouteConfig("test", "receiver")),
                mapOf(
                    "receiver" to Destination.Http("receiver", "http://localhost")
                )
            ),
            listOf(publisher),
            RetryStrategy(delivery),
            signal = signal
        )
        try {
            capture.start()
            withTimeout(60000) {
                while (!capture.healthy) {
                    check(!capture.recoveryRequired)
                    delay(100)
                }
            }
            // Wait for initial snapshot and offset persistence before testing live inserts.
            withTimeout(30000) { while (!Files.exists(directory.resolve("offsets.dat"))) delay(100) }
            poller.start()
            delay(1500)
            val message = OutboxMessage(topic = "test", payload = JsonObject(emptyMap()))
            repository.insert(message)
            assertEquals(
                message.id,
                withTimeout(DELIVERY_TIMEOUT_MS) { received.receive() }.id,
                "Delivery must beat the five-minute reconciliation timer"
            )
            // The publisher records the delivery before the poller marks the row, so this waits
            // for a state change that has already been decided. Five seconds was too tight on a
            // two-core runner.
            withTimeout(STATE_TIMEOUT_MS) { while (repository.countByState("sent") != 1L) delay(20) }
            assertNull(withTimeoutOrNull(1000) { received.receive() }, "State updates must not create deliveries")

            // A scheduled retry has no capture event of its own, because the retry only updates
            // the row. The scheduled deadline must therefore wake delivery well inside the
            // reconciliation timer.
            val retried = OutboxMessage(topic = "test", payload = JsonObject(emptyMap()))
            failOnce.add(retried.id)
            repository.insert(retried)
            assertEquals(
                retried.id,
                withTimeout(DELIVERY_TIMEOUT_MS) { received.receive() }.id,
                "The scheduled retry must beat the five-minute reconciliation timer"
            )
            withTimeout(STATE_TIMEOUT_MS) { while (repository.countByState("sent") != 2L) delay(20) }

            val second =
                OutboxCapture(
                    database,
                    config.copy(stateDirectory = Files.createTempDirectory("second-capture").toString()),
                    source,
                    signal
                )
            second.start()
            withTimeout(10000) { while (!second.recoveryRequired) delay(50) }
            assertFalse(second.healthy)
            second.shutdown()

            capture.shutdown()
            capture = OutboxCapture(database, config, source, signal)
            capture.start()
            withTimeout(60000) {
                while (!capture.healthy) {
                    check(!capture.recoveryRequired)
                    delay(100)
                }
            }
            val afterRestart = OutboxMessage(topic = "test", payload = JsonObject(emptyMap()))
            repository.insert(afterRestart)
            assertEquals(afterRestart.id, withTimeout(DELIVERY_TIMEOUT_MS) { received.receive() }.id)
            capture.shutdown()

            // Changed capture settings must never reuse the offsets of the previous settings.
            val reconfigured = OutboxCapture(database, config.copy(schema = "other_schema"), source, signal)
            reconfigured.start()
            withTimeout(10000) { while (!reconfigured.recoveryRequired) delay(50) }
            assertFalse(reconfigured.healthy)
            reconfigured.shutdown()

            if (database.type == "postgresql") {
                // Debezium recreates a dropped slot without an error, which discards the position.
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT pg_drop_replication_slot('" + config.slot + "')")
                    }
                }
                val withoutSlot = OutboxCapture(database, config, source, signal)
                withoutSlot.start()
                withTimeout(20000) { while (!withoutSlot.recoveryRequired) delay(50) }
                assertFalse(withoutSlot.healthy)
                withoutSlot.shutdown()
            } else {
                // A lost schema history must also be an operator decision.
                Files.delete(directory.resolve("history.dat"))
                val withoutHistory = OutboxCapture(database, config, source, signal)
                withoutHistory.start()
                withTimeout(20000) { while (!withoutHistory.recoveryRequired) delay(50) }
                assertFalse(withoutHistory.healthy)
                withoutHistory.shutdown()
                Files.writeString(directory.resolve("history.dat"), "")
            }

            Files.delete(directory.resolve("offsets.dat"))
            capture = OutboxCapture(database, config, source, signal)
            capture.start()
            withTimeout(10000) { while (!capture.recoveryRequired) delay(50) }
            assertFalse(capture.healthy)
            // SQL remains usable when capture state is lost.
            val recoverable = OutboxMessage(topic = "test", payload = JsonObject(emptyMap()))
            repository.insert(recoverable)
            signal.wake()
            assertEquals(recoverable.id, withTimeout(10000) { received.receive() }.id)
        } finally {
            capture.shutdown()
            poller.shutdown()
            directory.toFile().deleteRecursively()
        }
    }
}
