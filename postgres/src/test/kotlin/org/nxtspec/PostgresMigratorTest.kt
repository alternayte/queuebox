package org.nxtspec

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            password = container.password,
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
        outbox.markSent(claimed.single().id)
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
}
