package org.nxtspec

object ConfigValidator {
    fun validate(config: QueueBoxConfig): QueueBoxConfig {
        // Validate database URL format
        require(config.database.url.startsWith("jdbc:postgresql://")) {
            "Database URL must be PostgreSQL JDBC URL (jdbc:postgresql://...)"
        }

        // Validate port ranges
        require(config.server.httpPort in 1..65535) {
            "Invalid HTTP port: ${config.server.httpPort}. Must be between 1 and 65535"
        }

        // Validate pool size
        require(config.database.poolSize > 0) {
            "Database pool size must be greater than 0"
        }

        // Validate connection timeout
        require(config.database.connectionTimeoutMs > 0) {
            "Database connection timeout must be greater than 0"
        }

        // Validate outbox config
        require(config.outbox.pollIntervalMs > 0) {
            "Outbox poll interval must be greater than 0"
        }
        require(config.outbox.batchSize > 0) {
            "Outbox batch size must be greater than 0"
        }
        require(config.outbox.maxAttempts > 0) {
            "Outbox max attempts must be greater than 0"
        }

        // Validate destinations referenced in routes exist
        config.routes.forEach { route ->
            require(route.destination in config.destinations) {
                "Route references unknown destination: '${route.destination}'. Available destinations: ${config.destinations.keys}"
            }
        }

        return config
    }
}
