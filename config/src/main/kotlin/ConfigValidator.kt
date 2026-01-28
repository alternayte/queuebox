package org.nxtspec

/**
 * Validates QueueBox configuration after loading.
 *
 * Provides validation with error messages that reference both YAML paths
 * and environment variable names for better user experience.
 */
object ConfigValidator {

    /**
     * Validates a QueueBoxConfig and returns it if valid.
     *
     * @param config The configuration to validate
     * @return The validated configuration
     * @throws IllegalArgumentException if validation fails
     */
    fun validate(config: QueueBoxConfig): QueueBoxConfig {
        // Validate database URL format matches declared type
        val validUrlPrefixes = mapOf(
            "postgresql" to "jdbc:postgresql://",
            "sqlserver" to "jdbc:sqlserver://"
        )
        val expectedPrefix = validUrlPrefixes[config.database.type]
            ?: error("Unknown database type: '${config.database.type}'. Supported types: ${validUrlPrefixes.keys}")

        require(config.database.url.startsWith(expectedPrefix)) {
            "Database URL must match type '${config.database.type}' with prefix '$expectedPrefix'. " +
                "Set via 'database.url' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.url")} env var."
        }

        // Validate port ranges
        require(config.server.httpPort in 1..65535) {
            "Invalid HTTP port: ${config.server.httpPort}. Must be between 1 and 65535. " +
                "Set via 'server.httpPort' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("server.httpPort")} env var."
        }

        // Validate pool size
        require(config.database.poolSize > 0) {
            "Database pool size must be greater than 0. " +
                "Set via 'database.poolSize' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.poolSize")} env var."
        }

        // Validate connection timeout
        require(config.database.connectionTimeoutMs > 0) {
            "Database connection timeout must be greater than 0. " +
                "Set via 'database.connectionTimeoutMs' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.connectionTimeoutMs")} env var."
        }

        // Validate outbox config
        require(config.outbox.pollIntervalMs > 0) {
            "Outbox poll interval must be greater than 0. " +
                "Set via 'outbox.pollIntervalMs' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("outbox.pollIntervalMs")} env var."
        }
        require(config.outbox.batchSize > 0) {
            "Outbox batch size must be greater than 0. " +
                "Set via 'outbox.batchSize' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("outbox.batchSize")} env var."
        }
        require(config.outbox.maxAttempts > 0) {
            "Outbox max attempts must be greater than 0. " +
                "Set via 'outbox.maxAttempts' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("outbox.maxAttempts")} env var."
        }

        // Validate destinations referenced in routes exist
        config.routes.forEach { route ->
            require(route.destination in config.destinations) {
                "Route references unknown destination: '${route.destination}'. " +
                    "Available destinations: ${config.destinations.keys}. " +
                    "Define destinations via YAML 'destinations' section or QUEUEBOX_DESTINATIONS_* env vars."
            }
        }

        // Validate routes have required fields
        config.routes.forEachIndexed { index, route ->
            require(route.topicPattern.isNotBlank()) {
                "Route[$index] must have a non-empty topicPattern. " +
                    "Set via 'routes[$index].topicPattern' in YAML or QUEUEBOX_ROUTES_${index}_TOPICPATTERN env var."
            }
            require(route.destination.isNotBlank()) {
                "Route[$index] must have a non-empty destination. " +
                    "Set via 'routes[$index].destination' in YAML or QUEUEBOX_ROUTES_${index}_DESTINATION env var."
            }
        }

        // Validate retention configuration
        if (config.retention.enabled) {
            validateTableRetention("outbox", config.retention.outbox)
            validateTableRetention("inbox", config.retention.inbox)
        }

        return config
    }

    /**
     * Lists all required configuration fields with their YAML paths and env var names.
     * Useful for documentation and error messages.
     */
    fun getRequiredFields(): List<ConfigField> = listOf(
        ConfigField("database.type", EnvConfigLoader.yamlPathToEnvKey("database.type"), "Database type (postgresql or sqlserver)"),
        ConfigField("database.url", EnvConfigLoader.yamlPathToEnvKey("database.url"), "Database JDBC URL"),
        ConfigField("database.username", EnvConfigLoader.yamlPathToEnvKey("database.username"), "Database username"),
        ConfigField("database.password", EnvConfigLoader.yamlPathToEnvKey("database.password"), "Database password")
    )

    /**
     * Represents a configuration field with its YAML path, env var name, and description.
     */
    data class ConfigField(
        val yamlPath: String,
        val envVar: String,
        val description: String
    )

    /**
     * Validates table-specific retention configuration.
     *
     * @param table The table name (outbox or inbox) for error messages
     * @param config The table retention configuration to validate
     * @throws IllegalArgumentException if validation fails
     */
    private fun validateTableRetention(table: String, config: TableRetentionConfig) {
        when (config.policy) {
            RetentionPolicy.AGE -> {
                require(config.maxAge != null) {
                    "$table retention policy 'age' requires maxAge. " +
                        "Set via 'retention.$table.maxAge' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("retention.$table.maxAge")} env var."
                }
                // Validate maxAge format
                DurationParser.parse(config.maxAge)
            }
            RetentionPolicy.COUNT -> {
                require(config.maxCount != null && config.maxCount > 0) {
                    "$table retention policy 'count' requires positive maxCount. " +
                        "Set via 'retention.$table.maxCount' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("retention.$table.maxCount")} env var."
                }
            }
            RetentionPolicy.DISABLED -> {
                // No validation needed for disabled policy
            }
        }

        // Validate cleanupInterval format when policy is not disabled
        if (config.policy != RetentionPolicy.DISABLED) {
            DurationParser.parse(config.cleanupInterval)

            require(config.batchSize > 0) {
                "$table retention batchSize must be greater than 0. " +
                    "Set via 'retention.$table.batchSize' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("retention.$table.batchSize")} env var."
            }
        }
    }
}
