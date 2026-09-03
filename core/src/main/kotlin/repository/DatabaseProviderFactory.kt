package org.nxtspec.repository

import javax.sql.DataSource

/**
 * Supported database types for the repository layer.
 */
enum class DatabaseType {
    POSTGRESQL,
    SQLSERVER
}

/**
 * Configuration for custom column name mappings and table names.
 * This is a mirror of the config module's ColumnMappingConfig to avoid circular dependencies.
 * The actual values are passed from the config module at runtime.
 */
data class ColumnMappingData(
    val outbox: OutboxColumnMappingData = OutboxColumnMappingData(),
    val inbox: InboxColumnMappingData = InboxColumnMappingData(),
    val outboxTableName: String = "outbox",
    val inboxTableName: String = "inbox"
)

/**
 * Column name mapping data for the outbox table.
 */
data class OutboxColumnMappingData(
    val id: String = "id",
    val topic: String = "topic",
    val key: String = "key",
    val payload: String = "payload",
    val headers: String = "headers",
    val state: String = "state",
    val attempt: String = "attempt",
    val maxAttempts: String = "max_attempts",
    val scheduledAt: String = "scheduled_at",
    val createdAt: String = "created_at",
    val updatedAt: String = "updated_at",
    val claimedAt: String = "claimed_at",
    val lastError: String = "last_error"
)

/**
 * Column name mapping data for the inbox table.
 */
data class InboxColumnMappingData(
    val id: String = "id",
    val source: String = "source",
    val idempotencyKey: String = "idempotency_key",
    val aggregateId: String = "aggregate_id",
    val eventType: String = "event_type",
    val payload: String = "payload",
    val state: String = "state",
    val createdAt: String = "created_at",
    val processedAt: String = "processed_at",
    val claimedAt: String = "claimed_at"
)

/**
 * Factory interface for creating database-specific repository implementations.
 */
interface RepositoryFactory {
    fun createOutboxRepository(): OutboxRepositoryInterface
    fun createInboxRepository(): InboxRepositoryInterface
    fun createTransactionRunner(): TransactionRunner

    /** The migrator for this database. See F-030. */
    fun createMigrator(): Migrator
}

/**
 * Factory for creating database-specific repository factories.
 * Provides the appropriate RepositoryFactory based on the configured database type.
 */
object DatabaseProviderFactory {
    /**
     * Creates a RepositoryFactory for the specified database type.
     * @param type the type of database to create repositories for
     * @param dataSource the data source for database connections
     * @param columnMapping optional column name mapping configuration
     * @return a RepositoryFactory capable of creating repositories for the specified database type
     * @throws NotImplementedError if the database type is not yet supported
     */
    fun create(
        type: DatabaseType,
        dataSource: DataSource,
        columnMapping: ColumnMappingData = ColumnMappingData()
    ): RepositoryFactory = when (type) {
        DatabaseType.POSTGRESQL -> createPostgresFactory(dataSource, columnMapping)
        DatabaseType.SQLSERVER -> createSqlServerFactory(dataSource, columnMapping)
    }

    private fun createPostgresFactory(dataSource: DataSource, columnMapping: ColumnMappingData): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on postgres module
        val factoryClass = Class.forName("org.nxtspec.PostgresRepositoryFactory")
        val constructor = factoryClass.getConstructor(DataSource::class.java, ColumnMappingData::class.java)
        return constructor.newInstance(dataSource, columnMapping) as RepositoryFactory
    }

    private fun createSqlServerFactory(dataSource: DataSource, columnMapping: ColumnMappingData): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on sqlserver module
        val factoryClass = Class.forName("org.nxtspec.SqlServerRepositoryFactory")
        val constructor = factoryClass.getConstructor(DataSource::class.java, ColumnMappingData::class.java)
        return constructor.newInstance(dataSource, columnMapping) as RepositoryFactory
    }
}
