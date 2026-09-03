package org.nxtspec

import org.flywaydb.core.Flyway
import org.nxtspec.repository.Migrator
import javax.sql.DataSource

/**
 * Applies the PostgreSQL migration set with Flyway. See F-030.
 *
 * The location is provider specific, because both database modules can be on one classpath and
 * their version numbers correspond one to one.
 */
class PostgresMigrator : Migrator {
    override fun migrate(dataSource: DataSource): Int {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()

        return flyway.migrate().migrationsExecuted
    }

    companion object {
        const val LOCATION: String = "classpath:db/postgresql"
    }
}
