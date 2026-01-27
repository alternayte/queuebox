package org.nxtspec

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    fun create(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            connectionTimeout = config.connectionTimeoutMs
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }
        return HikariDataSource(hikariConfig)
    }

    fun init(dataSource: DataSource) {
        Database.connect(dataSource)
    }

    fun close(dataSource: HikariDataSource) {
        dataSource.close()
    }
}
