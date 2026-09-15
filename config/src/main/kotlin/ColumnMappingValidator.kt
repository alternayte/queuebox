package org.nxtspec

/** Validates the table column names that raw SQL strings interpolate. */
internal object ColumnMappingValidator {
    private fun setVia(yamlPath: String) =
        "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."

    /**
     * Validates that all column names in the mapping are safe SQL identifiers.
     * This prevents SQL injection since column names are interpolated into raw SQL strings.
     */
    fun validate(mapping: ColumnMappingConfig) {
        validateMappedColumns(
            "Outbox",
            "database.columnMapping.outbox",
            listOf(
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
                "claimToken" to mapping.outbox.claimToken,
                "leaseExpiresAt" to mapping.outbox.leaseExpiresAt,
                "lastError" to mapping.outbox.lastError
            )
        )
        validateMappedColumns(
            "Inbox",
            "database.columnMapping.inbox",
            listOf(
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
                "claimToken" to mapping.inbox.claimToken,
                "leaseExpiresAt" to mapping.inbox.leaseExpiresAt,
                "consumption" to mapping.inbox.consumption,
                "scheduledAt" to mapping.inbox.scheduledAt,
                "attempt" to mapping.inbox.attempt,
                "lastError" to mapping.inbox.lastError,
                "correlationId" to mapping.inbox.correlationId,
                "headers" to mapping.inbox.headers
            )
        )
    }

    private fun validateMappedColumns(kind: String, yamlPath: String, columns: List<Pair<String, String>>) {
        columns.forEach { (fieldName, columnName) ->
            require(columnName.isNotBlank()) {
                "$kind column name for '$fieldName' cannot be blank. " +
                    setVia("$yamlPath.$fieldName")
            }
            require(ConfigValidator.SQL_IDENTIFIER_REGEX.matches(columnName)) {
                "Invalid ${kind.lowercase()} column name '$columnName' for '$fieldName'. " +
                    "Column names must start with a letter or underscore and contain only " +
                    "alphanumeric characters and underscores. " +
                    setVia("$yamlPath.$fieldName")
            }
        }
    }
}
