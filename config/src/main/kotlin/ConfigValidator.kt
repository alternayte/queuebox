package org.nxtspec

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Validates QueueBox configuration after loading.
 *
 * Provides validation with error messages that reference both YAML paths
 * and environment variable names for better user experience.
 */
object ConfigValidator {

    /** Builds the standard hint that names the YAML path and the equivalent environment variable. */
    private fun setVia(yamlPath: String) =
        "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."

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
                setVia("database.url")
        }

        // Validate port ranges
        require(config.server.httpPort in 1..65535) {
            "Invalid HTTP port: ${config.server.httpPort}. Must be between 1 and 65535. " +
                setVia("server.httpPort")
        }

        // Validate pool size
        require(config.database.poolSize > 0) {
            "Database pool size must be greater than 0. " +
                setVia("database.poolSize")
        }

        // Validate connection timeout
        require(config.database.connectionTimeoutMs > 0) {
            "Database connection timeout must be greater than 0. " +
                setVia("database.connectionTimeoutMs")
        }

        // Validate outbox config
        require(config.outbox.pollIntervalMs > 0) {
            "Outbox poll interval must be greater than 0. " +
                setVia("outbox.pollIntervalMs")
        }
        require(config.outbox.batchSize > 0) {
            "Outbox batch size must be greater than 0. " +
                setVia("outbox.batchSize")
        }
        require(config.outbox.concurrency > 0) {
            "Outbox concurrency must be greater than 0. " +
                setVia("outbox.concurrency")
        }
        require(config.outbox.claimTimeoutMs > 0) {
            "Outbox claim timeout must be greater than 0. " +
                setVia("outbox.claimTimeoutMs")
        }
        require(config.outbox.shutdownTimeoutMs > 0) {
            "Outbox shutdown timeout must be greater than 0. " +
                setVia("outbox.shutdownTimeoutMs")
        }
        config.server.managementPort?.let { port ->
            require(port in 1..65535) {
                "Invalid management port: $port. Must be between 1 and 65535. " +
                    setVia("server.managementPort")
            }
            require(port != config.server.httpPort) {
                "The management port must differ from the HTTP port. Both are $port. " +
                    setVia("server.managementPort")
            }
        }
        require(config.database.startupTimeoutMs > 0) {
            "Database startup timeout must be greater than 0. " +
                setVia("database.startupTimeoutMs")
        }
        require(config.admin.maxTransformTimeoutMs > 0) {
            "Admin max transform timeout must be greater than 0. " +
                setVia("admin.maxTransformTimeoutMs")
        }
        require(config.admin.maxPayloadBytes > 0) {
            "Admin max payload bytes must be greater than 0. " +
                setVia("admin.maxPayloadBytes")
        }
        require(config.http.maxErrorBodyBytes > 0) {
            "HTTP max error body bytes must be greater than 0. " +
                setVia("http.maxErrorBodyBytes")
        }
        if (config.admin.auth != null) {
            validateInboxAuth(config.admin.auth, "Admin", "admin.auth")
        }
        require(config.inbox.maxBodyBytes > 0) {
            "Inbox max body bytes must be greater than 0. " +
                setVia("inbox.maxBodyBytes")
        }
        require(config.outbox.maxAttempts > 0) {
            "Outbox max attempts must be greater than 0. " +
                setVia("outbox.maxAttempts")
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
            validateTopicPattern(route.topicPattern, index)
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
            validateExtractionPaths(name, source)
            source.rateLimit?.let {
                require(it.requestsPerMinute > 0) {
                    "Source '$name' rateLimit.requestsPerMinute must be greater than 0. " +
                        "Set via 'sources.$name.rateLimit.requestsPerMinute' in YAML or " +
                        "${EnvConfigLoader.yamlPathToEnvKey("sources.$name.rateLimit.requestsPerMinute")} env var."
                }
            }
            if (source is SourceConfig.Http) {
                validateInboxAuth(source.auth, "Source '$name'", "sources.$name.auth")
            }
        }

        // Validate destination auth and destination URL
        config.destinations.forEach { (name, dest) ->
            if (dest is DestinationConfig.Http) {
                validateDestinationAuth(
                    dest.auth,
                    "Destination '$name'",
                    "destinations.$name.auth",
                    config.http.blockPrivateAddresses
                )
                validateDestinationUrl(name, dest.baseUrl, config.http.blockPrivateAddresses)
                validateDestinationPath(name, dest.path)
            }
        }

        // Validate retention configuration
        if (config.retention.enabled) {
            validateTableRetention("outbox", config.retention.outbox)
            validateTableRetention("inbox", config.retention.inbox)
            require(config.retention.inbox.policy != RetentionPolicy.COUNT) {
                "The inbox retention does not support the count policy. QueueBox deletes no " +
                    "inbox row under that policy, so the table grows without bound. Use " +
                    "'age' or 'disabled'. " + setVia("retention.inbox.policy")
            }
        }

        // The relay ceiling. See the third review gate, defect 1.
        config.inbox.relay.maxAttempts?.let { ceiling ->
            require(ceiling > 0) {
                "The relay maxAttempts must be greater than 0. " + setVia("inbox.relay.maxAttempts")
            }
        }

        // Validate column mapping
        validateColumnMapping(config.database.columnMapping)

        // Validate table names (F-011)
        validateTableNames(config.database)

        // The relay stamps a dead-letter ceiling on every row it creates. An operator who does
        // not name a relay ceiling gets the configured outbox ceiling, so 'outbox.maxAttempts'
        // holds for a relayed message too. See the third review gate, defect 1.
        if (config.inbox.relay.maxAttempts == null) {
            return config.copy(
                inbox = config.inbox.copy(
                    relay = config.inbox.relay.copy(maxAttempts = config.outbox.maxAttempts)
                )
            )
        }

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
    /**
     * Validates a route topic pattern. See F-026.
     *
     * A pattern accepts letters, digits, and the characters `_`, `-`, `.` and `*`. The router
     * treats every other character as a literal, so a pattern that holds one is a configuration
     * mistake. A run of more than two `*` has no meaning.
     */
    private fun validateTopicPattern(pattern: String, index: Int) {
        // MessageRouter escapes every literal segment, so a regular expression metacharacter in
        // a pattern is a literal and is safe. The validator therefore rejects only what the
        // glob syntax cannot express.
        require(!pattern.contains("***")) {
            "Route[$index] has an invalid topicPattern: '$pattern'. Use '*' for one segment and " +
                "'**' for any number of segments. " +
                "Set via 'routes[$index].topicPattern' in YAML or QUEUEBOX_ROUTES_${index}_TOPICPATTERN env var."
        }
    }

    /**
     * Refuses an extraction path that is invalid or indefinite. See the fourth review gate.
     *
     * An indefinite path, such as `$..orderId`, matches any number of nodes. The number depends
     * on the message, so the idempotency key of a source becomes unpredictable. QueueBox refuses
     * such a path at startup instead of a rejection for every message.
     */
    private fun validateExtractionPaths(name: String, source: SourceConfig) {
        val paths = when (source) {
            is SourceConfig.Http -> listOf(
                "idempotencyKeyPath" to source.idempotencyKeyPath,
                "aggregateIdPath" to source.aggregateIdPath,
                "eventTypePath" to source.eventTypePath
            )
            is SourceConfig.RabbitMQ -> listOf(
                "idempotencyKeyPath" to source.idempotencyKeyPath,
                "aggregateIdPath" to source.aggregateIdPath,
                "eventTypePath" to source.eventTypePath
            )
        }

        paths.forEach { (field, path) ->
            if (path == null) {
                return@forEach
            }
            val yamlPath = "sources.$name.$field"
            val definite = try {
                IdempotencyExtractor.isDefinitePath(path)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Source '$name' $field '$path' is not a valid JSONPath expression: " +
                        "${e.message}. " + setVia(yamlPath)
                )
            }
            require(definite) {
                "Source '$name' $field '$path' is an indefinite JSONPath expression. It matches " +
                    "any number of nodes, so QueueBox cannot read one value from it. Use a " +
                    "definite path, such as '\$.data.orderId'. " + setVia(yamlPath)
            }
        }
    }

    private fun validateSourceTopic(name: String, source: SourceConfig) {
        require(source.topic.isNotBlank()) {
            "Source '$name' topic template cannot be blank. " +
                "Set via 'sources.$name.topic' in YAML or " +
                "${EnvConfigLoader.yamlPathToEnvKey("sources.$name.topic")} env var."
        }

        if (!source.topic.contains("eventType")) {
            return
        }

        when (source) {
            is SourceConfig.Http ->
                require(source.eventTypePath != null) {
                    "Source '$name' topic template '${source.topic}' uses eventType, but " +
                        "'sources.$name.eventTypePath' is not set. The inbox relay would mark every " +
                        "message of this source as dead. Set 'sources.$name.eventTypePath', or set a " +
                        "'sources.$name.topic' template that does not use eventType."
                }

            is SourceConfig.RabbitMQ ->
                // Fifth review gate. An AMQP source has two sources of the event type: the
                // 'eventTypePath' in the body, and the 'x-event-type' header. A publisher that
                // sets neither gives an empty topic, and the relay marks the message dead. The
                // header cannot be checked at startup, so the operator declares it.
                require(source.eventTypePath != null || source.eventTypeFromHeader) {
                    "Source '$name' topic template '${source.topic}' uses eventType, but " +
                        "'sources.$name.eventTypePath' is not set and " +
                        "'sources.$name.eventTypeFromHeader' is false. The inbox relay would mark " +
                        "every message with no event type as dead. Set " +
                        "'sources.$name.eventTypePath', or set " +
                        "'sources.$name.eventTypeFromHeader' to true when every publisher sets the " +
                        "'x-event-type' AMQP header, or set a 'sources.$name.topic' template that " +
                        "does not use eventType."
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
                    setVia(yamlPath)
            }
            require(SQL_IDENTIFIER_REGEX.matches(tableName)) {
                "Invalid table name '$tableName' for '$yamlPath'. " +
                    "Table names must start with a letter or underscore and contain only " +
                    "alphanumeric characters and underscores. " +
                    setVia(yamlPath)
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
            "claimedAt" to mapping.outbox.claimedAt,
            "lastError" to mapping.outbox.lastError
        )

        outboxColumns.forEach { (fieldName, columnName) ->
            require(columnName.isNotBlank()) {
                "Outbox column name for '$fieldName' cannot be blank. " +
                    setVia("database.columnMapping.outbox.$fieldName")
            }
            require(sqlIdentifierRegex.matches(columnName)) {
                "Invalid outbox column name '$columnName' for '$fieldName'. " +
                    "Column names must start with a letter or underscore and contain only " +
                    "alphanumeric characters and underscores. " +
                    setVia("database.columnMapping.outbox.$fieldName")
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
            "claimedAt" to mapping.inbox.claimedAt,
            "correlationId" to mapping.inbox.correlationId
        )

        inboxColumns.forEach { (fieldName, columnName) ->
            require(columnName.isNotBlank()) {
                "Inbox column name for '$fieldName' cannot be blank. " +
                    setVia("database.columnMapping.inbox.$fieldName")
            }
            require(sqlIdentifierRegex.matches(columnName)) {
                "Invalid inbox column name '$columnName' for '$fieldName'. " +
                    "Column names must start with a letter or underscore and contain only " +
                    "alphanumeric characters and underscores. " +
                    setVia("database.columnMapping.inbox.$fieldName")
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
                    setVia("$yamlPath.expression")
            }
            require(it.timeoutMs > 0) {
                "$context transform timeoutMs must be positive. " +
                    setVia("$yamlPath.timeoutMs")
            }
            require(it.maxDepth > 0) {
                "$context transform maxDepth must be positive. " +
                    setVia("$yamlPath.maxDepth")
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
                            setVia("$yamlPath.token")
                    }
                }
                is InboxAuthConfig.ApiKey -> {
                    require(it.key.isNotBlank()) {
                        "$context auth API key cannot be blank. " +
                            setVia("$yamlPath.key")
                    }
                    require(it.headerName.isNotBlank()) {
                        "$context auth header name cannot be blank. " +
                            setVia("$yamlPath.headerName")
                    }
                }
                is InboxAuthConfig.HmacSignature -> {
                    require(it.timestampTolerance > 0) {
                        "$context auth timestampTolerance must be greater than 0. " +
                            "Set via '$yamlPath.timestampTolerance' in YAML or " +
                            "${EnvConfigLoader.yamlPathToEnvKey("$yamlPath.timestampTolerance")} env var."
                    }
                    require(
                        it.signaturePayloadFormat != SignaturePayloadFormat.TIMESTAMP_DOT_BODY ||
                            it.timestampHeader != null
                    ) {
                        "$context auth signaturePayloadFormat is 'timestamp-dot-body', but " +
                            "'$yamlPath.timestampHeader' is not set. Every request would then " +
                            "fail with a missing timestamp header. Set the timestamp header, or " +
                            "use the 'body' format."
                    }
                    require(it.secret.isNotBlank()) {
                        "$context HMAC secret cannot be blank. " +
                            setVia("$yamlPath.secret")
                    }
                    require(it.algorithm in listOf("HmacSHA256", "HmacSHA512")) {
                        "$context HMAC algorithm must be HmacSHA256 or HmacSHA512. " +
                            setVia("$yamlPath.algorithm")
                    }
                    require(it.headerName.isNotBlank()) {
                        "$context HMAC signature header name cannot be blank. " +
                            setVia("$yamlPath.headerName")
                    }
                }
            }
        }
    }

    /**
     * Validates DestinationAuthConfig if present.
     */
    private fun validateDestinationAuth(
        auth: DestinationAuthConfig?,
        context: String,
        yamlPath: String,
        blockPrivateAddresses: Boolean
    ) {
        auth?.let {
            when (it) {
                is DestinationAuthConfig.OAuth2 -> {
                    // F-040: the token URL carries the client secret in the request body, so it
                    // needs the same checks as a destination base URL.
                    validateHttpUrl(
                        it.tokenUrl,
                        "$context tokenUrl",
                        "$yamlPath.tokenUrl",
                        blockPrivateAddresses
                    )
                    require(it.clientId.isNotBlank()) {
                        "$context OAuth2 clientId cannot be blank. " +
                            setVia("$yamlPath.clientId")
                    }
                    require(it.clientSecret.isNotBlank()) {
                        "$context OAuth2 clientSecret cannot be blank. " +
                            setVia("$yamlPath.clientSecret")
                    }
                    require(it.tokenUrl.isNotBlank()) {
                        "$context OAuth2 tokenUrl cannot be blank. " +
                            setVia("$yamlPath.tokenUrl")
                    }
                }
                is DestinationAuthConfig.Basic -> {
                    require(it.username.isNotBlank()) {
                        "$context Basic auth username cannot be blank. " +
                            setVia("$yamlPath.username")
                    }
                }
                is DestinationAuthConfig.Header -> {
                    require(it.headerName.isNotBlank()) {
                        "$context header name cannot be blank. " +
                            setVia("$yamlPath.headerName")
                    }
                    require(it.headerValue.isNotBlank()) {
                        "$context header value cannot be blank. " +
                            setVia("$yamlPath.headerValue")
                    }
                }
            }
        }
    }

    /**
     * Validates the base URL of an HTTP destination. See F-040.
     *
     * The base URL must parse as an absolute URL with the scheme `http` or `https` and with a
     * host. When `http.blockPrivateAddresses` is true, the validator resolves the host and
     * refuses a loopback address, a link-local address, a site-local address, and an address in
     * the unique-local IPv6 range `fc00::/7`.
     *
     * A host name that does not resolve does not stop the start. The validator cannot decide the
     * address of such a host, so it accepts the destination and the publish attempt reports the
     * failure later.
     */
    private fun validateDestinationUrl(name: String, baseUrl: String, blockPrivateAddresses: Boolean) {
        validateHttpUrl(baseUrl, "Destination '$name' baseUrl", "destinations.$name.baseUrl", blockPrivateAddresses)
    }

    /**
     * Refuses a destination path that carries a dot segment. See F-040.
     *
     * A URL builder appends the segments as they are. A server or an intermediary then resolves
     * `..`, which changes the target.
     */
    private fun validateDestinationPath(name: String, path: String) {
        val yamlPath = "destinations.$name.path"
        val segments = path.split('/')

        require(segments.none { it == ".." || it == "." }) {
            "Destination '$name' path '$path' must not carry a '.' or a '..' segment, because " +
                "that changes the target after the server resolves it. " +
                setVia(yamlPath)
        }
    }

    /**
     * Validates one outbound URL. See F-040.
     *
     * The URL must be an absolute HTTP or HTTPS URL with a host. It must not carry a userinfo
     * component, because that puts a credential into a field that prints in clear text.
     */
    private fun validateHttpUrl(url: String, label: String, yamlPath: String, blockPrivateAddresses: Boolean) {
        val hint = setVia(yamlPath)

        require(url.isNotBlank()) {
            "$label cannot be blank. $hint"
        }

        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("$label '${CredentialMasking.maskUrl(url)}' is not a valid URL. $hint")
        }

        // F-038: a rejected URL can still carry a password. Every message that prints the value
        // masks it first. The user information check below prints no value at all.
        val shown = CredentialMasking.maskUrl(url)

        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "$label '$shown' must be an absolute http or https URL. $hint"
        }

        val host = uri.host
        require(!host.isNullOrBlank()) {
            "$label '$shown' must carry a host. $hint"
        }

        require(uri.userInfo == null) {
            "$label must not carry a user name or a password in the URL. A credential in a URL " +
                "prints in clear text. Use the 'auth' block instead. $hint"
        }

        if (blockPrivateAddresses) {
            requirePublicHost(label, url, host, hint)
        }
    }

    /**
     * Refuses a host that resolves to a private address.
     */
    private fun requirePublicHost(label: String, url: String, host: String, hint: String) {
        val addresses = try {
            InetAddress.getAllByName(host.trim('[', ']')).toList()
        } catch (e: UnknownHostException) {
            // The host does not resolve here. Accept it, so a temporary DNS fault does not stop
            // the start.
            return
        }

        addresses.forEach { address ->
            require(!isPrivateAddress(address)) {
                "$label '$url' resolves to the private address " +
                    "${address.hostAddress}, and 'http.blockPrivateAddresses' is true. $hint"
            }
        }
    }

    /**
     * Reports whether an address is loopback, link-local, site-local, or unique-local IPv6.
     */
    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress
        ) {
            return true
        }
        val bytes = address.address
        // Unique-local IPv6 is fc00::/7.
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    /**
     * Lists all required configuration fields with their YAML paths and env var names.
     * Useful for documentation and error messages.
     */
    fun getRequiredFields(): List<ConfigField> = listOf(
        ConfigField(
            "database.type",
            EnvConfigLoader.yamlPathToEnvKey("database.type"),
            "Database type (postgresql or sqlserver)"
        ),
        ConfigField("database.url", EnvConfigLoader.yamlPathToEnvKey("database.url"), "Database JDBC URL"),
        ConfigField("database.username", EnvConfigLoader.yamlPathToEnvKey("database.username"), "Database username"),
        ConfigField("database.password", EnvConfigLoader.yamlPathToEnvKey("database.password"), "Database password")
    )

    /**
     * Represents a configuration field with its YAML path, env var name, and description.
     */
    data class ConfigField(val yamlPath: String, val envVar: String, val description: String)

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
                        setVia("retention.$table.maxAge")
                }
                // Validate maxAge format
                DurationParser.parse(config.maxAge)
            }
            RetentionPolicy.COUNT -> {
                require(config.maxCount != null && config.maxCount > 0) {
                    "$table retention policy 'count' requires positive maxCount. " +
                        setVia("retention.$table.maxCount")
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
                    setVia("retention.$table.batchSize")
            }
        }
    }
}
