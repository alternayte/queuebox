package org.nxtspec

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlServerDynamicTablesTest : SqlServerTestBase() {

    @AfterEach
    fun dropDynamicTables() {
        // Clean up any dynamic tables created during tests
        transaction {
            exec("IF OBJECT_ID('custom_outbox', 'U') IS NOT NULL DROP TABLE custom_outbox")
            exec("IF OBJECT_ID('custom_inbox', 'U') IS NOT NULL DROP TABLE custom_inbox")
        }
    }

    @Test
    fun `SqlServerDynamicOutboxTable should create with default column names`() {
        val table = createDefaultSqlServerOutboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "test.topic"
                it[payload] = """{"data": "test"}"""
                it[headers] = "{}"
                it[state] = "pending"
                it[attempt] = 0
                it[maxAttempts] = 5
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("test.topic", result[table.topic])
        assertEquals("pending", result[table.state])
    }

    @Test
    fun `SqlServerDynamicOutboxTable should create with custom column names`() {
        val customMapping = OutboxColumnMapping(
            id = "msg_id",
            topic = "event_type",
            key = "partition_key",
            payload = "data",
            headers = "meta",
            state = "status",
            attempt = "retry_count",
            maxAttempts = "max_retries",
            scheduledAt = "next_run",
            createdAt = "created_at",
            updatedAt = "modified_at"
        )
        val table = SqlServerDynamicOutboxTable(customMapping, "custom_outbox")

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "custom.event"
                it[payload] = """{"custom": true}"""
                it[headers] = "{}"
                it[state] = "processing"
                it[attempt] = 1
                it[maxAttempts] = 3
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("custom.event", result[table.topic])
        assertEquals("processing", result[table.state])
        assertEquals(1, result[table.attempt])
    }

    @Test
    fun `SqlServerDynamicInboxTable should create with default column names`() {
        val table = createDefaultSqlServerInboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[messageSrc] = "webhook-source"
                it[idempotencyKey] = "msg-123"
                it[aggregateId] = "order-456"
                it[eventType] = "order.created"
                it[payload] = """{"order": "data"}"""
                it[state] = "pending"
                it[createdAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("webhook-source", result[table.messageSrc])
        assertEquals("msg-123", result[table.idempotencyKey])
        assertEquals("order-456", result[table.aggregateId])
    }

    @Test
    fun `SqlServerDynamicInboxTable should create with custom column names`() {
        val customMapping = InboxColumnMapping(
            id = "msg_id",
            source = "origin",
            idempotencyKey = "dedup_key",
            aggregateId = "entity_id",
            eventType = "event_name",
            payload = "body",
            state = "status",
            createdAt = "received_at",
            processedAt = "completed_at"
        )
        val table = SqlServerDynamicInboxTable(customMapping, "custom_inbox")

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[messageSrc] = "custom-source"
                it[idempotencyKey] = "custom-key"
                it[aggregateId] = "agg-789"
                it[eventType] = "custom.event"
                it[payload] = """{"custom": "data"}"""
                it[state] = "processed"
                it[createdAt] = now
                it[processedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("custom-source", result[table.messageSrc])
        assertEquals("custom-key", result[table.idempotencyKey])
    }

    @Test
    fun `SqlServerDynamicInboxTable should create indexes`() {
        val table = createDefaultSqlServerInboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        // Verify indexes exist by querying SQL Server system views
        val indexCount = transaction {
            var count = 0
            exec("SELECT name FROM sys.indexes WHERE object_id = OBJECT_ID('inbox') AND name IS NOT NULL") { rs ->
                while (rs.next()) {
                    count++
                }
            }
            count
        }

        // Should have at least the unique index and the aggregate_id/state index
        assertTrue(indexCount!! >= 2, "Expected at least 2 indexes, found $indexCount")
    }

    @Test
    fun `quoteSqlServerIdentifier should quote every identifier`() {
        assertEquals("[key]", quoteSqlServerIdentifier("key"))
        assertEquals("[order]", quoteSqlServerIdentifier("order"))
        assertEquals("[topic]", quoteSqlServerIdentifier("topic"))
        assertEquals("[payload]", quoteSqlServerIdentifier("payload"))
        assertEquals("[created_at]", quoteSqlServerIdentifier("created_at"))
        assertEquals("[KEY]", quoteSqlServerIdentifier("KEY"))
        assertEquals("[my_schema_outbox]", quoteSqlServerIdentifier("my_schema_outbox"))
    }

    @Test
    fun `quoteSqlServerIdentifier should reject a closing bracket`() {
        assertFailsWith<IllegalArgumentException> {
            quoteSqlServerIdentifier("outbox] ; DROP TABLE users --")
        }
    }

    @Test
    fun `createDefaultSqlServerInboxTable should return table with defaults`() {
        val table = createDefaultSqlServerInboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[messageSrc] = "source"
                it[idempotencyKey] = "key"
                it[payload] = "{}"
                it[createdAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("pending", result[table.state]) // Default value
    }

    @Test
    fun `SqlServerDynamicOutboxTable should support nullable key column`() {
        val table = createDefaultSqlServerOutboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        // Insert with key
        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "test.topic"
                it[key] = "partition-key"
                it[payload] = "{}"
                it[headers] = "{}"
                it[state] = "pending"
                it[attempt] = 0
                it[maxAttempts] = 5
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("partition-key", result[table.key])

        // Insert without key (null)
        val id2 = UUID.randomUUID()
        transaction {
            table.insert {
                it[table.id] = id2
                it[topic] = "test.topic2"
                it[payload] = "{}"
                it[headers] = "{}"
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result2 = transaction {
            table.selectAll().where { table.id eq id2 }.singleOrNull()
        }

        assertNotNull(result2)
        assertEquals(null, result2[table.key])
    }
}
