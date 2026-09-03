package org.nxtspec.repository

import javax.sql.DataSource

/**
 * Applies the bundled schema migrations. See F-030.
 *
 * Each database module ships its own migration set and its own implementation. An operator whose
 * application user has no DDL rights sets `database.migrate` to false and applies the SQL files
 * by hand. `docs/development/migrations.md` describes the policy.
 */
interface Migrator {
    /**
     * Applies every migration that the database has not applied yet.
     *
     * @return the number of migrations that this call applied
     */
    fun migrate(dataSource: DataSource): Int
}
