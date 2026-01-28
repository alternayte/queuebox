package org.nxtspec

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Factory for creating SQL Server database connections with HikariCP connection pooling.
 * Supports JDBC URLs in the format: jdbc:sqlserver://host:1433;databaseName=queuebox
 */
object SqlServerDatabaseFactory {

    /**
     * Creates a HikariDataSource configured for SQL Server.
     */
    fun create(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.username
            password = config.password
            driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
            maximumPoolSize = config.poolSize
            connectionTimeout = config.connectionTimeoutMs
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }
        return HikariDataSource(hikariConfig)
    }

    /**
     * Connects Exposed ORM to the provided data source.
     */
    fun init(dataSource: DataSource) {
        Database.connect(dataSource)
    }

    /**
     * Gracefully closes the connection pool.
     */
    fun close(dataSource: HikariDataSource) {
        dataSource.close()
    }
}
