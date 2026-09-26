package org.nxtspec.repository

import javax.sql.DataSource

/**
 * Applies the bundled schema migrations. See F-030.
 *
 * Each database module ships its own migration set and its own implementation. An operator whose
 * application user has no DDL rights sets `database.migrate` to false and runs `queuebox migrate`
 * with a privileged user. `docs/development/migrations.md` describes the policy.
 */
interface Migrator {
    /**
     * Applies every migration that the database has not applied yet.
     *
     * A database with no migration history and none of [tables] baselines at version 0 first, so
     * a database that holds only application tables migrates as before.
     *
     * @param tables the QueueBox tables. One of them without a migration history stops the call.
     * @return the versions that this call applied, in the order it applied them
     * @throws MigrationHistoryMissingException when one of [tables] exists and the history does not
     */
    fun migrate(dataSource: DataSource, tables: Collection<String>): List<String>

    /**
     * Records [version] as applied without running it or an earlier file, then applies the
     * later files. This turns a database whose files an operator applied by hand into a normal
     * one.
     *
     * @return the versions that this call applied after the baseline
     * @throws MigrationHistoryExistsException when the database already has a migration history
     * @throws IllegalArgumentException when [version] is not a bundled migration version
     */
    fun baseline(dataSource: DataSource, version: String): List<String>
}

/**
 * QueueBox tables exist, but the database has no migration history.
 *
 * Somebody applied the files by hand. A baseline at version 0 would replay every file, and a
 * guessed version can skip a migration without a sign, so only the operator can name the version.
 */
class MigrationHistoryMissingException :
    RuntimeException(
        "QueueBox tables exist without a migration history. Run `queuebox migrate --baseline <version>` " +
            "with the last version you applied by hand."
    )

/**
 * `--baseline` met a database that already has a migration history. A second baseline would hide
 * the real state, so the call changes nothing.
 */
class MigrationHistoryExistsException :
    RuntimeException(
        "The database already has a migration history, so QueueBox refuses to record a baseline. " +
            "Run `queuebox migrate` without --baseline."
    )

/**
 * Returns the names in [tables] that exist in the default schema of the connection.
 *
 * Flyway works in the default schema, so a table of the same name in another schema does not
 * count. PostgreSQL folds an unquoted name to lower case and the metadata lookup matches exactly,
 * so the lookup tries each case.
 */
fun existingTables(dataSource: DataSource, tables: Collection<String>): List<String> =
    dataSource.connection.use { conn ->
        val schema = conn.schema
        // The name is a LIKE pattern, so an underscore in a table name must not match any letter.
        val escape = conn.metaData.searchStringEscape
        tables.filter { table ->
            setOf(table, table.lowercase(), table.uppercase()).any { name ->
                val pattern = name.replace("_", "${escape}_").replace("%", "$escape%")
                conn.metaData.getTables(conn.catalog, schema, pattern, arrayOf("TABLE")).use { it.next() }
            }
        }
    }
