package org.nxtspec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicTablesTest : PostgresTestBase() {

    @AfterEach
    fun dropDynamicTables() {
        // Clean up any dynamic tables created during tests
        transaction {
            exec("DROP TABLE IF EXISTS custom_outbox CASCADE")
            exec("DROP TABLE IF EXISTS custom_inbox CASCADE")
        }
    }

    @Test
    fun `DynamicOutboxTable should create with default column names`() {
        val table = createDefaultOutboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        // Verify we can insert and query data
        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "test.topic"
                it[payload] = JsonObject(mapOf("data" to JsonPrimitive("test")))
                it[headers] = JsonObject(emptyMap())
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
    fun `DynamicOutboxTable should create with custom column names`() {
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
        val table = DynamicOutboxTable(customMapping, "custom_outbox")

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "custom.event"
                it[payload] = JsonObject(mapOf("custom" to JsonPrimitive(true)))
                it[headers] = JsonObject(emptyMap())
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
    fun `DynamicOutboxTable should support all column operations`() {
        val table = createDefaultOutboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        // Test insert with all columns including nullable key
        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "full.test"
                it[key] = "user-123"
                it[payload] = JsonObject(mapOf("full" to JsonPrimitive("payload")))
                it[headers] = JsonObject(mapOf("header" to JsonPrimitive("value")))
                it[state] = "sent"
                it[attempt] = 2
                it[maxAttempts] = 10
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("user-123", result[table.key])
        assertEquals(2, result[table.attempt])
        assertEquals(10, result[table.maxAttempts])
    }

    @Test
    fun `DynamicInboxTable should create with default column names`() {
        val table = createDefaultInboxTable()

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
                it[payload] = JsonObject(mapOf("order" to JsonPrimitive("data")))
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
    fun `DynamicInboxTable should create with custom column names`() {
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
        val table = DynamicInboxTable(customMapping, "custom_inbox")

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
                it[payload] = JsonObject(mapOf("custom" to JsonPrimitive("data")))
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
    fun `DynamicInboxTable should create unique index on source and idempotencyKey`() {
        val table = createDefaultInboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        val now = kotlinx.datetime.Clock.System.now()

        // Insert first message
        transaction {
            table.insert {
                it[table.id] = UUID.randomUUID()
                it[messageSrc] = "test-source"
                it[idempotencyKey] = "unique-key"
                it[payload] = JsonObject(emptyMap())
                it[state] = "pending"
                it[createdAt] = now
            }
        }

        // Attempt to insert duplicate should fail
        var exceptionThrown = false
        try {
            transaction {
                table.insert {
                    it[table.id] = UUID.randomUUID()
                    it[messageSrc] = "test-source"
                    it[idempotencyKey] = "unique-key" // Same source + key combo
                    it[payload] = JsonObject(emptyMap())
                    it[state] = "pending"
                    it[createdAt] = now
                }
            }
        } catch (e: Exception) {
            exceptionThrown = true
            // Expected to fail due to unique constraint
            assertTrue(e.message?.contains("duplicate key", ignoreCase = true) == true ||
                    e.message?.contains("unique constraint", ignoreCase = true) == true ||
                    e.cause?.message?.contains("duplicate key", ignoreCase = true) == true ||
                    e.cause?.message?.contains("unique constraint", ignoreCase = true) == true)
        }

        assertTrue(exceptionThrown, "Expected unique constraint violation")
    }

    @Test
    fun `DynamicInboxTable should create index on aggregateId and state`() {
        val table = createDefaultInboxTable()

        transaction {
            SchemaUtils.create(table)
        }

        // Verify index exists by checking table metadata
        val hasIndex = transaction {
            exec("SELECT indexname FROM pg_indexes WHERE tablename = 'inbox' AND indexdef LIKE '%aggregate_id%'") { rs ->
                rs.next()
            }
        }

        assertTrue(hasIndex == true, "Expected index on aggregateId to exist")
    }

    @Test
    fun `createDefaultOutboxTable should return table with defaults`() {
        val table = createDefaultOutboxTable()

        // Verify table has expected default column names by checking it can be created
        transaction {
            SchemaUtils.create(table)
        }

        // Insert with default state and attempt values
        val id = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()

        transaction {
            table.insert {
                it[table.id] = id
                it[topic] = "default.topic"
                it[payload] = JsonObject(emptyMap())
                it[scheduledAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("pending", result[table.state]) // Default value
        assertEquals(0, result[table.attempt]) // Default value
        assertEquals(5, result[table.maxAttempts]) // Default value
    }

    @Test
    fun `createDefaultInboxTable should return table with defaults`() {
        val table = createDefaultInboxTable()

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
                it[payload] = JsonObject(emptyMap())
                it[createdAt] = now
            }
        }

        val result = transaction {
            table.selectAll().where { table.id eq id }.singleOrNull()
        }

        assertNotNull(result)
        assertEquals("pending", result[table.state]) // Default value
    }
}
