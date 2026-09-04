package org.nxtspec.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the column mapping defaults that every database module reads.
 */
class ColumnMappingDataTest {

    @Test
    fun `outbox defaults match the shipped schema`() {
        val mapping = OutboxColumnMappingData()

        assertEquals("id", mapping.id)
        assertEquals("topic", mapping.topic)
        assertEquals("key", mapping.key)
        assertEquals("payload", mapping.payload)
        assertEquals("headers", mapping.headers)
        assertEquals("state", mapping.state)
        assertEquals("attempt", mapping.attempt)
        assertEquals("max_attempts", mapping.maxAttempts)
        assertEquals("scheduled_at", mapping.scheduledAt)
        assertEquals("created_at", mapping.createdAt)
        assertEquals("updated_at", mapping.updatedAt)
        assertEquals("claimed_at", mapping.claimedAt)
        assertEquals("last_error", mapping.lastError)
    }

    @Test
    fun `inbox defaults match the shipped schema`() {
        val mapping = InboxColumnMappingData()

        assertEquals("id", mapping.id)
        assertEquals("source", mapping.source)
        assertEquals("idempotency_key", mapping.idempotencyKey)
        assertEquals("aggregate_id", mapping.aggregateId)
        assertEquals("event_type", mapping.eventType)
        assertEquals("payload", mapping.payload)
        assertEquals("state", mapping.state)
        assertEquals("created_at", mapping.createdAt)
        assertEquals("processed_at", mapping.processedAt)
        assertEquals("claimed_at", mapping.claimedAt)
    }

    @Test
    fun `column mapping data carries the table names`() {
        val data = ColumnMappingData(
            outboxTableName = "my_outbox",
            inboxTableName = "my_inbox"
        )

        assertEquals("my_outbox", data.outboxTableName)
        assertEquals("my_inbox", data.inboxTableName)
        assertEquals(OutboxColumnMappingData(), data.outbox)
        assertEquals(InboxColumnMappingData(), data.inbox)
    }

    @Test
    fun `the factory reaches the module lookup for every supported type`() {
        // The core module alone carries no database implementation, so the reflective lookup
        // fails. The assertion proves the factory handles every supported type.
        DatabaseType.entries.forEach { type ->
            assertFailsWith<ClassNotFoundException> {
                DatabaseProviderFactory.create(type, FakeDataSource())
            }
        }
    }

    private class FakeDataSource : javax.sql.DataSource {
        override fun getConnection() = throw UnsupportedOperationException()
        override fun getConnection(username: String?, password: String?) = throw UnsupportedOperationException()

        override fun getLogWriter() = throw UnsupportedOperationException()
        override fun setLogWriter(out: java.io.PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = throw UnsupportedOperationException()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?) = false
    }
}
