package org.nxtspec.app

import org.nxtspec.DatabaseConfig
import org.nxtspec.InboxColumnMapping
import org.nxtspec.OutboxColumnMapping

/**
 * Thrown at startup when the bundled migrations cannot create the configured schema.
 */
class MigrationNotApplicableException(message: String) : RuntimeException(message)

/**
 * The bundled migration files name the default table and column names. They cannot create a
 * custom schema. QueueBox therefore refuses to run them against a custom schema, instead of
 * creating the default tables that no query then reads. See F-030.
 *
 * @throws MigrationNotApplicableException when the configuration renames a table or a column
 */
fun requireDefaultSchemaForMigrations(database: DatabaseConfig) {
    val renamed = mutableListOf<String>()

    if (database.outboxTableName != DEFAULT_OUTBOX_TABLE) {
        renamed.add("database.outboxTableName")
    }
    if (database.inboxTableName != DEFAULT_INBOX_TABLE) {
        renamed.add("database.inboxTableName")
    }
    if (database.columnMapping.outbox != OutboxColumnMapping()) {
        renamed.add("database.columnMapping.outbox")
    }
    if (database.columnMapping.inbox != InboxColumnMapping()) {
        renamed.add("database.columnMapping.inbox")
    }

    if (renamed.isEmpty()) return

    throw MigrationNotApplicableException(
        "The bundled migrations create the default schema, so they cannot create the schema " +
            "that ${renamed.joinToString(", ")} describes. Set 'database.migrate' to false and " +
            "apply your own schema. docs/development/migrations.md holds the SQL that the " +
            "default schema uses."
    )
}

private const val DEFAULT_OUTBOX_TABLE = "outbox"
private const val DEFAULT_INBOX_TABLE = "inbox"
