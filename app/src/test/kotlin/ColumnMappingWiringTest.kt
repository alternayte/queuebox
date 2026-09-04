package org.nxtspec.app

import org.junit.jupiter.api.Test
import org.nxtspec.ColumnMappingConfig
import org.nxtspec.DatabaseConfig
import org.nxtspec.InboxColumnMapping
import org.nxtspec.OutboxColumnMapping
import org.nxtspec.Secret
import kotlin.test.assertEquals

/**
 * The column mapping of the configuration must reach the repository factory complete. See the
 * fourth review gate, defect 2. A dropped name passes the validator and then fails on every
 * insert.
 */
class ColumnMappingWiringTest {

    private fun databaseConfig(): DatabaseConfig = DatabaseConfig(
        url = "jdbc:postgresql://localhost:5432/queuebox",
        username = "queuebox",
        password = Secret("secret"),
        columnMapping = ColumnMappingConfig(
            outbox = OutboxColumnMapping(
                id = "o_id",
                topic = "o_topic",
                key = "o_key",
                payload = "o_payload",
                headers = "o_headers",
                state = "o_state",
                attempt = "o_attempt",
                maxAttempts = "o_max_attempts",
                scheduledAt = "o_scheduled_at",
                createdAt = "o_created_at",
                updatedAt = "o_updated_at",
                claimedAt = "o_claimed_at",
                lastError = "o_last_error"
            ),
            inbox = InboxColumnMapping(
                id = "i_id",
                source = "i_source",
                idempotencyKey = "i_idempotency_key",
                aggregateId = "i_aggregate_id",
                eventType = "i_event_type",
                payload = "i_payload",
                state = "i_state",
                createdAt = "i_created_at",
                processedAt = "i_processed_at",
                claimedAt = "i_claimed_at",
                correlationId = "i_correlation_id"
            )
        ),
        outboxTableName = "my_outbox",
        inboxTableName = "my_inbox"
    )

    @Test
    fun `every inbox column name reaches the factory`() {
        val mapping = columnMappingData(databaseConfig()).inbox

        assertEquals("i_id", mapping.id)
        assertEquals("i_source", mapping.source)
        assertEquals("i_idempotency_key", mapping.idempotencyKey)
        assertEquals("i_aggregate_id", mapping.aggregateId)
        assertEquals("i_event_type", mapping.eventType)
        assertEquals("i_payload", mapping.payload)
        assertEquals("i_state", mapping.state)
        assertEquals("i_created_at", mapping.createdAt)
        assertEquals("i_processed_at", mapping.processedAt)
        assertEquals("i_claimed_at", mapping.claimedAt)
        assertEquals("i_correlation_id", mapping.correlationId, "The correlation column must be wired.")
    }

    @Test
    fun `every outbox column name reaches the factory`() {
        val mapping = columnMappingData(databaseConfig()).outbox

        assertEquals("o_id", mapping.id)
        assertEquals("o_topic", mapping.topic)
        assertEquals("o_key", mapping.key)
        assertEquals("o_payload", mapping.payload)
        assertEquals("o_headers", mapping.headers)
        assertEquals("o_state", mapping.state)
        assertEquals("o_attempt", mapping.attempt)
        assertEquals("o_max_attempts", mapping.maxAttempts)
        assertEquals("o_scheduled_at", mapping.scheduledAt)
        assertEquals("o_created_at", mapping.createdAt)
        assertEquals("o_updated_at", mapping.updatedAt)
        assertEquals("o_claimed_at", mapping.claimedAt)
        assertEquals("o_last_error", mapping.lastError)
    }

    @Test
    fun `the table names reach the factory`() {
        val data = columnMappingData(databaseConfig())

        assertEquals("my_outbox", data.outboxTableName)
        assertEquals("my_inbox", data.inboxTableName)
    }
}
