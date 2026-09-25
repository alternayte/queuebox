package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers F-030. QueueBox must create its own schema against an empty database.
 *
 * This class starts its own container with no init script, so nothing but the migrations
 * creates a table.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMigratorTest {

    private val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
        .withDatabaseName("queuebox_migrate")
        .withUsername("test")
        .withPassword("test")
        .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
        .also { it.start() }

    @AfterAll
    fun stopContainer() {
        container.stop()
    }

    @Test
    fun `migrate creates the schema and the message path works`() = runBlocking {
        val config = DatabaseConfig(
            url = container.jdbcUrl,
            username = container.username,
            password = Secret(container.password),
            poolSize = 5
        )
        val dataSource = DatabaseFactory.create(config)

        val applied = PostgresMigrator().migrate(dataSource)
        assertTrue(applied >= 4, "Every bundled migration must run. Applied $applied")

        // A second run must be a no-op.
        assertEquals(0, PostgresMigrator().migrate(dataSource))

        DatabaseFactory.init(dataSource)

        val outbox = OutboxRepository()
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

        val inbox = InboxRepository()
        assertEquals(
            InboxResult.Stored,
            inbox.store(
                InboxMessage(
                    source = "stripe",
                    idempotencyKey = "evt_1",
                    eventType = "payment.succeeded",
                    payload = JsonObject(emptyMap())
                )
            )
        )
        assertEquals(1, inbox.claimPending(10).size)

        DatabaseFactory.close(dataSource)
    }

    @Test
    fun `migrate succeeds against a schema that an operator already created`() = runBlocking {
        // The Compose file and the manual procedure both create the tables outside Flyway.
        // Flyway then baselines the database and replays every file, so every file must be
        // safe to run twice.
        val databaseName = "queuebox_existing"
        java.sql.DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password
        ).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") }
        }

        val url = container.jdbcUrl.substringBeforeLast('/') + "/" + databaseName
        val config = DatabaseConfig(
            url = url,
            username = container.username,
            password = Secret(container.password),
            poolSize = 5
        )
        val dataSource = DatabaseFactory.create(config)

        // The operator applies the shipped SQL by hand first.
        dataSource.connection.use { connection ->
            listOf(
                "V1__create_outbox.sql",
                "V2__create_inbox.sql",
                "V3__add_claimed_at.sql",
                "V4__add_last_error.sql"
            ).forEach { name ->
                val sql = requireNotNull(
                    javaClass.getResourceAsStream("/db/postgresql/$name")
                ) { "Migration $name must be on the classpath" }.bufferedReader().readText()
                connection.createStatement().use { it.execute(sql) }
            }
        }

        // Flyway must not fail on the replay.
        val applied = PostgresMigrator().migrate(dataSource)
        assertTrue(applied >= 4, "Flyway records every file. Applied $applied")

        DatabaseFactory.close(dataSource)
    }

    /**
     * Covers F-090's DoD requirement: the migration must apply to a database that already holds
     * rows, not only to an empty one.
     */
    @Test
    fun `V9 applies to a populated outbox table`() = runBlocking {
        val databaseName = "queuebox_v9_populated"
        java.sql.DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password
        ).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") }
        }

        val url = container.jdbcUrl.substringBeforeLast('/') + "/" + databaseName
        val config = DatabaseConfig(
            url = url,
            username = container.username,
            password = Secret(container.password),
            poolSize = 5
        )
        val dataSource = DatabaseFactory.create(config)
        try {
            // Migrate only up to V8, so the row insert below happens against the schema that
            // predates this feature.
            Flyway.configure()
                .dataSource(dataSource)
                .locations(PostgresMigrator.LOCATION)
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
                        "VALUES (gen_random_uuid(), ?, '{}'::jsonb, 'pending', 0, 5, now(), now(), now())"
                ).use { stmt ->
                    repeat(rowCount) {
                        stmt.setString(1, "order.created")
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                connection.commit()
            }

            val applied = PostgresMigrator().migrate(dataSource)
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
            DatabaseFactory.close(dataSource)
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
        java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection -> connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") } }
        val config = DatabaseConfig(
            url = container.jdbcUrl.substringBeforeLast('/') + "/" + databaseName,
            username = container.username,
            password = Secret(container.password),
            poolSize = 5
        )
        val dataSource = DatabaseFactory.create(config)
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(PostgresMigrator.LOCATION)
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
                        "VALUES (gen_random_uuid(), ?, '{}'::jsonb, 'pending', now(), ?, now())"
                ).use { stmt ->
                    topicsByAge.forEach { (topic, minute) ->
                        stmt.setString(1, topic)
                        stmt.setTimestamp(2, java.sql.Timestamp.valueOf("2026-01-01 00:0$minute:00"))
                        stmt.executeUpdate()
                    }
                }
                connection.commit()
            }

            PostgresMigrator().migrate(dataSource)

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
            val sql = requireNotNull(javaClass.getResourceAsStream("/db/postgresql/V11__add_outbox_sequence.sql"))
                .bufferedReader().readText()
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute(sql) }
                connection.commit()
            }
            assertEquals(backfilled, sequenceByTopic())

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO outbox (id, topic, payload, state, scheduled_at, created_at, updated_at) " +
                        "VALUES (gen_random_uuid(), ?, '{}'::jsonb, 'pending', now(), ?, now())"
                ).use { stmt ->
                    stmt.setString(1, "new")
                    stmt.setTimestamp(2, java.sql.Timestamp.valueOf("2020-01-01 00:00:00"))
                    stmt.executeUpdate()
                }
                connection.commit()
            }
            assertTrue(sequenceByTopic().getValue("new") > backfilled.values.max())
        } finally {
            DatabaseFactory.close(dataSource)
        }
    }
}
