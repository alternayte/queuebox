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
    val claimedAt: String = "claimed_at",
    val correlationId: String = "correlation_id"
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
 * The provider module for a database type is absent from the class path. See F-080.
 * The message names the Gradle module that the user must add.
 */
class MissingDatabaseProviderException(
    val type: DatabaseType,
    val module: String,
    val className: String,
    cause: ClassNotFoundException
) : RuntimeException(
    "The database provider for $type is absent from the class path. " +
        "The class $className was not found. Add the Gradle module '$module' to the runtime " +
        "class path, for example with runtimeOnly(project(\":$module\")).",
    cause
)

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
     * @throws MissingDatabaseProviderException if the provider module is absent from the class path
     */
    fun create(
        type: DatabaseType,
        dataSource: DataSource,
        columnMapping: ColumnMappingData = ColumnMappingData()
    ): RepositoryFactory = create(type, dataSource, columnMapping, defaultClassLoader())

    /**
     * The seam that the test uses. It takes the class loader that resolves the provider class, so
     * a test can prove the behaviour when the provider module is absent. See F-080.
     */
    internal fun create(
        type: DatabaseType,
        dataSource: DataSource,
        columnMapping: ColumnMappingData,
        classLoader: ClassLoader
    ): RepositoryFactory = when (type) {
        DatabaseType.POSTGRESQL -> createPostgresFactory(dataSource, columnMapping, classLoader)
        DatabaseType.SQLSERVER -> createSqlServerFactory(dataSource, columnMapping, classLoader)
    }

    private fun defaultClassLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: DatabaseProviderFactory::class.java.classLoader

    /** Resolves a provider class, and reports an absent module with a named error. See F-080. */
    private fun loadProviderClass(
        className: String,
        type: DatabaseType,
        module: String,
        classLoader: ClassLoader
    ): Class<*> = try {
        Class.forName(className, true, classLoader)
    } catch (cause: ClassNotFoundException) {
        throw MissingDatabaseProviderException(type, module, className, cause)
    }

    private fun createPostgresFactory(
        dataSource: DataSource,
        columnMapping: ColumnMappingData,
        classLoader: ClassLoader
    ): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on postgres module
        val factoryClass = loadProviderClass(
            "org.nxtspec.PostgresRepositoryFactory",
            DatabaseType.POSTGRESQL,
            "postgres",
            classLoader
        )
        val constructor = factoryClass.getConstructor(DataSource::class.java, ColumnMappingData::class.java)
        return constructor.newInstance(dataSource, columnMapping) as RepositoryFactory
    }

    private fun createSqlServerFactory(
        dataSource: DataSource,
        columnMapping: ColumnMappingData,
        classLoader: ClassLoader
    ): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on sqlserver module
        val factoryClass = loadProviderClass(
            "org.nxtspec.SqlServerRepositoryFactory",
            DatabaseType.SQLSERVER,
            "sqlserver",
            classLoader
        )
        val constructor = factoryClass.getConstructor(DataSource::class.java, ColumnMappingData::class.java)
        return constructor.newInstance(dataSource, columnMapping) as RepositoryFactory
    }
}
