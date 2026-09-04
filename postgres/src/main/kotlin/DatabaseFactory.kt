package org.nxtspec

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    /**
     * Create a HikariDataSource with optional metrics integration.
     * When a MeterRegistry is provided, HikariCP metrics will be automatically
     * exposed including connection pool statistics and acquisition timings.
     */
    fun create(config: DatabaseConfig, registry: MeterRegistry? = null): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.username
            password = config.password.reveal()
            maximumPoolSize = config.poolSize
            connectionTimeout = config.connectionTimeoutMs
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Enable Micrometer metrics if registry is provided
            if (registry != null) {
                metricsTrackerFactory = MicrometerMetricsTrackerFactory(registry)
            }
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
