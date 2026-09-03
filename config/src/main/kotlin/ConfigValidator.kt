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
            validateTransform(route.transform, "Route[$index]", "routes[$index].transform")
        }

        // Validate destination transforms
        config.destinations.forEach { (name, dest) ->
            validateTransform(dest.transform, "Destination '$name'", "destinations.$name.transform")
        }

        // Validate source transforms and auth
        config.sources.forEach { (name, source) ->
            validateTransform(source.transform, "Source '$name'", "sources.$name.transform")
            validateSourceTopic(name, source)
            if (source is SourceConfig.Http) {
                validateInboxAuth(source.auth, "Source '$name'", "sources.$name.auth")
            }
        }

        // Validate destination auth
        config.destinations.forEach { (name, dest) ->
            if (dest is DestinationConfig.Http) {
                validateDestinationAuth(dest.auth, "Destination '$name'", "destinations.$name.auth")
            }
        }

        // Validate retention configuration
        if (config.retention.enabled) {
            validateTableRetention("outbox", config.retention.outbox)
            validateTableRetention("inbox", config.retention.inbox)
        }

        // Validate column mapping
        validateColumnMapping(config.database.columnMapping)

        // Validate table names (F-011)
        validateTableNames(config.database)

        return config
    }

    /**
     * Valid SQL identifier: starts with a letter or an underscore, and contains only
     * alphanumeric characters and underscores.
     */
    private val SQL_IDENTIFIER_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    /**
     * Validates the source topic template that the inbox relay renders. See F-002.
     *
     * The relay marks a message dead when the template renders empty. An HTTP source that uses
     * `{{ eventType }}` without an `eventTypePath` therefore loses every message. Reject that
     * configuration at startup.
     */
    private fun validateSourceTopic(name: String, source: SourceConfig) {
        require(source.topic.isNotBlank()) {
            "Source '$name' topic template cannot be blank. " +
                "Set via 'sources.$name.topic' in YAML or " +
                "${EnvConfigLoader.yamlPathToEnvKey("sources.$name.topic")} env var."
        }

        if (source is SourceConfig.Http && source.topic.contains("eventType")) {
            require(source.eventTypePath != null) {
                "Source '$name' topic template '${source.topic}' uses eventType, but " +
                    "'sources.$name.eventTypePath' is not set. The inbox relay would mark every " +
                    "message of this source as dead. Set 'sources.$name.eventTypePath', or set a " +
                    "'sources.$name.topic' template that does not use eventType."
            }
        }
    }

    /**
     * Validates that the configured table names are safe SQL identifiers.
     *
     * Table names are interpolated into raw SQL strings, so an unchecked value becomes
     * arbitrary SQL. The repositories also quote every interpolated identifier. Both
     * defences are required.
     */
    private fun validateTableNames(database: DatabaseConfig) {
        val tableNames = listOf(
            "database.outboxTableName" to database.outboxTableName,
            "database.inboxTableName" to database.inboxTableName
        )

        tableNames.forEach { (yamlPath, tableName) ->
            require(tableName.isNotBlank()) {
                "Table name '$yamlPath' cannot be blank. " +
                    "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."
            }
            require(SQL_IDENTIFIER_REGEX.matches(tableName)) {
                "Invalid table name '$tableName' for '$yamlPath'. " +
                    "Table names must start with a letter or underscore and contain only " +
                    "alphanumeric characters and underscores. " +
                    "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."
            }
        }
    }

    /**
     * Validates that all column names in the mapping are safe SQL identifiers.
     * This prevents SQL injection since column names are interpolated into raw SQL strings.
     */
    private fun validateColumnMapping(mapping: ColumnMappingConfig) {
        val sqlIdentifierRegex = SQL_IDENTIFIER_REGEX

        // Validate outbox column names
        val outboxColumns = listOf(
            "id" to mapping.outbox.id,
            "topic" to mapping.outbox.topic,
            "key" to mapping.outbox.key,
            "payload" to mapping.outbox.payload,
            "headers" to mapping.outbox.headers,
            "state" to mapping.outbox.state,
            "attempt" to mapping.outbox.attempt,
            "maxAttempts" to mapping.outbox.maxAttempts,
            "scheduledAt" to mapping.outbox.scheduledAt,
            "createdAt" to mapping.outbox.createdAt,
            "updatedAt" to mapping.outbox.updatedAt,
            "claimedAt" to mapping.outbox.claimedAt
        )

        outboxColumns.forEach { (fieldName, columnName) ->
            require(columnName.isNotBlank()) {
                "Outbox column name for '$fieldName' cannot be blank. " +
                    "Set via 'database.columnMapping.outbox.$fieldName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.columnMapping.outbox.$fieldName")} env var."
            }
            require(sqlIdentifierRegex.matches(columnName)) {
                "Invalid outbox column name '$columnName' for '$fieldName'. " +
                    "Column names must start with a letter or underscore and contain only alphanumeric characters and underscores. " +
                    "Set via 'database.columnMapping.outbox.$fieldName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.columnMapping.outbox.$fieldName")} env var."
            }
        }

        // Validate inbox column names
        val inboxColumns = listOf(
            "id" to mapping.inbox.id,
            "source" to mapping.inbox.source,
            "idempotencyKey" to mapping.inbox.idempotencyKey,
            "aggregateId" to mapping.inbox.aggregateId,
            "eventType" to mapping.inbox.eventType,
            "payload" to mapping.inbox.payload,
            "state" to mapping.inbox.state,
            "createdAt" to mapping.inbox.createdAt,
            "processedAt" to mapping.inbox.processedAt,
            "claimedAt" to mapping.inbox.claimedAt
        )

        inboxColumns.forEach { (fieldName, columnName) ->
            require(columnName.isNotBlank()) {
                "Inbox column name for '$fieldName' cannot be blank. " +
                    "Set via 'database.columnMapping.inbox.$fieldName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.columnMapping.inbox.$fieldName")} env var."
            }
            require(sqlIdentifierRegex.matches(columnName)) {
                "Invalid inbox column name '$columnName' for '$fieldName'. " +
                    "Column names must start with a letter or underscore and contain only alphanumeric characters and underscores. " +
                    "Set via 'database.columnMapping.inbox.$fieldName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("database.columnMapping.inbox.$fieldName")} env var."
            }
        }
    }

    /**
     * Validates a TransformConfig if present.
     */
    private fun validateTransform(transform: TransformConfig?, context: String, yamlPath: String) {
        transform?.let {
            require(it.expression.isNotBlank()) {
                "$context transform expression cannot be blank. " +
                    "Set via '$yamlPath.expression' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.expression")} env var."
            }
            require(it.timeoutMs > 0) {
                "$context transform timeoutMs must be positive. " +
                    "Set via '$yamlPath.timeoutMs' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.timeoutMs")} env var."
            }
            require(it.maxDepth > 0) {
                "$context transform maxDepth must be positive. " +
                    "Set via '$yamlPath.maxDepth' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.maxDepth")} env var."
            }
        }
    }

    /**
     * Validates InboxAuthConfig if present.
     */
    private fun validateInboxAuth(auth: InboxAuthConfig?, context: String, yamlPath: String) {
        auth?.let {
            when (it) {
                is InboxAuthConfig.Bearer -> {
                    require(it.token.isNotBlank()) {
                        "$context auth bearer token cannot be blank. " +
                            "Set via '$yamlPath.token' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.token")} env var."
                    }
                }
                is InboxAuthConfig.ApiKey -> {
                    require(it.key.isNotBlank()) {
                        "$context auth API key cannot be blank. " +
                            "Set via '$yamlPath.key' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.key")} env var."
                    }
                    require(it.headerName.isNotBlank()) {
                        "$context auth header name cannot be blank. " +
                            "Set via '$yamlPath.headerName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.headerName")} env var."
                    }
                }
                is InboxAuthConfig.HmacSignature -> {
                    require(it.secret.isNotBlank()) {
                        "$context HMAC secret cannot be blank. " +
                            "Set via '$yamlPath.secret' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.secret")} env var."
                    }
                    require(it.algorithm in listOf("HmacSHA256", "HmacSHA512")) {
                        "$context HMAC algorithm must be HmacSHA256 or HmacSHA512. " +
                            "Set via '$yamlPath.algorithm' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.algorithm")} env var."
                    }
                    require(it.headerName.isNotBlank()) {
                        "$context HMAC signature header name cannot be blank. " +
                            "Set via '$yamlPath.headerName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.headerName")} env var."
                    }
                }
            }
        }
    }

    /**
     * Validates DestinationAuthConfig if present.
     */
    private fun validateDestinationAuth(auth: DestinationAuthConfig?, context: String, yamlPath: String) {
        auth?.let {
            when (it) {
                is DestinationAuthConfig.OAuth2 -> {
                    require(it.clientId.isNotBlank()) {
                        "$context OAuth2 clientId cannot be blank. " +
                            "Set via '$yamlPath.clientId' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.clientId")} env var."
                    }
                    require(it.clientSecret.isNotBlank()) {
                        "$context OAuth2 clientSecret cannot be blank. " +
                            "Set via '$yamlPath.clientSecret' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.clientSecret")} env var."
                    }
                    require(it.tokenUrl.isNotBlank()) {
                        "$context OAuth2 tokenUrl cannot be blank. " +
                            "Set via '$yamlPath.tokenUrl' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.tokenUrl")} env var."
                    }
                }
                is DestinationAuthConfig.Basic -> {
                    require(it.username.isNotBlank()) {
                        "$context Basic auth username cannot be blank. " +
                            "Set via '$yamlPath.username' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.username")} env var."
                    }
                }
                is DestinationAuthConfig.Header -> {
                    require(it.headerName.isNotBlank()) {
                        "$context header name cannot be blank. " +
                            "Set via '$yamlPath.headerName' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.headerName")} env var."
                    }
                    require(it.headerValue.isNotBlank()) {
                        "$context header value cannot be blank. " +
                            "Set via '$yamlPath.headerValue' in YAML or ${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.headerValue")} env var."
                    }
                }
            }
        }
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
