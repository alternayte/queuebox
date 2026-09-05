package org.nxtspec

import org.nxtspec.repository.ColumnMappingData
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.repository.Migrator
import org.nxtspec.repository.OutboxRepositoryInterface
import org.nxtspec.repository.RepositoryFactory
import org.nxtspec.repository.TransactionRunner
import javax.sql.DataSource

/**
 * Factory for creating SQL Server-specific repository implementations.
 */
class SqlServerRepositoryFactory(
    // Kept because it is part of the public constructor contract of this factory.
    @Suppress("UnusedPrivateProperty")
    private val dataSource: DataSource,
    private val columnMapping: ColumnMappingData = ColumnMappingData()
) : RepositoryFactory {

    private val outboxColumnMapping = OutboxColumnMapping(
        id = columnMapping.outbox.id,
        topic = columnMapping.outbox.topic,
        key = columnMapping.outbox.key,
        payload = columnMapping.outbox.payload,
        headers = columnMapping.outbox.headers,
        state = columnMapping.outbox.state,
        attempt = columnMapping.outbox.attempt,
        maxAttempts = columnMapping.outbox.maxAttempts,
        scheduledAt = columnMapping.outbox.scheduledAt,
        createdAt = columnMapping.outbox.createdAt,
        updatedAt = columnMapping.outbox.updatedAt,
        claimedAt = columnMapping.outbox.claimedAt,
        claimToken = columnMapping.outbox.claimToken,
        leaseExpiresAt = columnMapping.outbox.leaseExpiresAt,
        lastError = columnMapping.outbox.lastError
    )

    private val inboxColumnMapping = InboxColumnMapping(
        id = columnMapping.inbox.id,
        source = columnMapping.inbox.source,
        idempotencyKey = columnMapping.inbox.idempotencyKey,
        aggregateId = columnMapping.inbox.aggregateId,
        eventType = columnMapping.inbox.eventType,
        payload = columnMapping.inbox.payload,
        state = columnMapping.inbox.state,
        createdAt = columnMapping.inbox.createdAt,
        processedAt = columnMapping.inbox.processedAt,
        claimedAt = columnMapping.inbox.claimedAt,
        claimToken = columnMapping.inbox.claimToken,
        leaseExpiresAt = columnMapping.inbox.leaseExpiresAt,
        consumption = columnMapping.inbox.consumption,
        scheduledAt = columnMapping.inbox.scheduledAt,
        attempt = columnMapping.inbox.attempt,
        lastError = columnMapping.inbox.lastError,
        correlationId = columnMapping.inbox.correlationId
    )

    override fun createOutboxRepository(): OutboxRepositoryInterface =
        SqlServerOutboxRepository(outboxColumnMapping, columnMapping.outboxTableName)

    override fun createInboxRepository(): InboxRepositoryInterface =
        SqlServerInboxRepository(inboxColumnMapping, columnMapping.inboxTableName)

    override fun createTransactionRunner(): TransactionRunner = SqlServerTransactionRunner()

    override fun createMigrator(): Migrator = SqlServerMigrator()
}
