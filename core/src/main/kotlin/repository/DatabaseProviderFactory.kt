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
 * Factory interface for creating database-specific repository implementations.
 */
interface RepositoryFactory {
    fun createOutboxRepository(): OutboxRepositoryInterface
    fun createInboxRepository(): InboxRepositoryInterface
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
     * @return a RepositoryFactory capable of creating repositories for the specified database type
     * @throws NotImplementedError if the database type is not yet supported
     */
    fun create(type: DatabaseType, dataSource: DataSource): RepositoryFactory = when (type) {
        DatabaseType.POSTGRESQL -> createPostgresFactory(dataSource)
        DatabaseType.SQLSERVER -> createSqlServerFactory(dataSource)
    }

    private fun createPostgresFactory(dataSource: DataSource): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on postgres module
        val factoryClass = Class.forName("org.nxtspec.PostgresRepositoryFactory")
        val constructor = factoryClass.getConstructor(DataSource::class.java)
        return constructor.newInstance(dataSource) as RepositoryFactory
    }

    private fun createSqlServerFactory(dataSource: DataSource): RepositoryFactory {
        // Use reflection to avoid compile-time dependency on sqlserver module
        val factoryClass = Class.forName("org.nxtspec.SqlServerRepositoryFactory")
        val constructor = factoryClass.getConstructor(DataSource::class.java)
        return constructor.newInstance(dataSource) as RepositoryFactory
    }
}
