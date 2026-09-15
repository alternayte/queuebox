package org.nxtspec.app

import org.nxtspec.DatabaseConfig
import org.nxtspec.repository.DatabaseType
import javax.sql.DataSource

/**
 * Stops the start when the inbox table has no headers column.
 *
 * The bundled migration V10 adds the column to the default schema. A custom inbox table does not
 * get the migration, and every inbox insert would then fail one message at a time.
 */
fun requireInboxHeadersColumn(dataSource: DataSource, database: DatabaseConfig, type: DatabaseType) {
    val table = database.inboxTableName
    val column = database.columnMapping.inbox.headers
    val present = dataSource.connection.use { conn ->
        // Postgres folds an unquoted name to lower case, and the metadata lookup matches exactly.
        setOf(table, table.lowercase(), table.uppercase()).any { name ->
            conn.metaData.getColumns(null, null, name, null).use { rows ->
                generateSequence { if (rows.next()) rows.getString("COLUMN_NAME") else null }
                    .any { it.equals(column, ignoreCase = true) }
            }
        }
    }
    if (present) return

    val alter = when (type) {
        DatabaseType.POSTGRESQL -> "ALTER TABLE \"$table\" ADD COLUMN \"$column\" JSONB NOT NULL DEFAULT '{}';"
        DatabaseType.SQLSERVER -> "ALTER TABLE [$table] ADD [$column] NVARCHAR(MAX) NOT NULL DEFAULT '{}';"
    }
    throw StartupFailedException(
        "The inbox table '$table' has no column '$column'. QueueBox stores the message headers " +
            "there. Add the column, then start QueueBox again: $alter"
    )
}
