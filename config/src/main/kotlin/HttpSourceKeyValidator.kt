package org.nxtspec

/**
 * Validates where an HTTP source reads its idempotency key, event type and aggregate ID.
 * See `docs/specs/header-keys.md` and issue #80.
 */
internal object HttpSourceKeyValidator {
    private fun setVia(yamlPath: String) =
        "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."

    /** The headers that QueueBox never stores, whatever the auth block of the source is. */
    private val credentialHeaders = listOf("Authorization", "Proxy-Authorization", "Cookie")

    fun validate(name: String, source: SourceConfig.Http) {
        require(
            source.idempotencyKeyPath != null ||
                source.idempotencyKeyHeader != null ||
                source.idempotencyKeyExpression != null
        ) {
            "Source '$name' has no idempotency key. Set 'sources.$name.idempotencyKeyPath' to a " +
                "JSONPath in the body, 'sources.$name.idempotencyKeyHeader' to a request header, " +
                "or 'sources.$name.idempotencyKeyExpression' to a JSONata expression. " +
                setVia("sources.$name.idempotencyKeyPath")
        }

        val authHeader = when (val auth = source.auth) {
            is InboxAuthConfig.ApiKey -> auth.headerName
            is InboxAuthConfig.HmacSignature -> auth.headerName
            is InboxAuthConfig.Bearer, null -> null
        }
        val forbidden = credentialHeaders.map { it to "QueueBox never stores it" } +
            listOfNotNull(authHeader?.let { it to "'sources.$name.auth.headerName' names it" })

        listOf(
            "idempotencyKeyHeader" to source.idempotencyKeyHeader,
            "eventTypeHeader" to source.eventTypeHeader,
            "aggregateIdHeader" to source.aggregateIdHeader,
            "scheduledAtHeader" to source.scheduledAtHeader
        ).forEach { (field, header) ->
            if (header == null) return@forEach
            val yamlPath = "sources.$name.$field"
            require(header.isNotBlank()) {
                "Source '$name' $field cannot be blank. Remove the key, or name a header. " + setVia(yamlPath)
            }
            val clash = forbidden.firstOrNull { (credential, _) -> credential.equals(header.trim(), ignoreCase = true) }
            require(clash == null) {
                "Source '$name' $field '$header' names a credential header, and $field cannot read " +
                    "one: ${clash!!.second}, so the header never reaches the inbox. Name another " +
                    "header. " + setVia(yamlPath)
            }
        }
    }
}
