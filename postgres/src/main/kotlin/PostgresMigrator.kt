package org.nxtspec

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.nxtspec.repository.MigrationHistoryExistsException
import org.nxtspec.repository.MigrationHistoryMissingException
import org.nxtspec.repository.Migrator
import org.nxtspec.repository.existingTables
import javax.sql.DataSource

/**
 * Applies the PostgreSQL migration set with Flyway. See F-030.
 *
 * The location is provider specific, because both database modules can be on one classpath and
 * their version numbers correspond one to one.
 */
class PostgresMigrator : Migrator {
    override fun migrate(dataSource: DataSource, tables: Collection<String>): List<String> {
        val flyway = flyway(dataSource, baselineVersion = "0")
        if (!flyway.hasHistory() && existingTables(dataSource, tables).isNotEmpty()) {
            throw MigrationHistoryMissingException()
        }
        return flyway.migrate().migrations.map { it.version }
    }

    override fun baseline(dataSource: DataSource, version: String): List<String> {
        val flyway = flyway(dataSource, baselineVersion = "0")
        if (flyway.hasHistory()) throw MigrationHistoryExistsException()

        val bundled = flyway.info().all().mapNotNull { it.version }
        val requested = requireNotNull(
            parseVersion(version)?.let { parsed -> bundled.firstOrNull { it == parsed } }
        ) {
            "The version '$version' is not a bundled migration. The bundled versions are " +
                "${bundled.joinToString(", ") { it.version }}."
        }

        val baselined = flyway(dataSource, baselineVersion = requested.version)
        baselined.baseline()
        return baselined.migrate().migrations.map { it.version }
    }

    companion object {
        const val LOCATION: String = "classpath:db/postgresql"

        private fun flyway(dataSource: DataSource, baselineVersion: String): Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .baselineOnMigrate(true)
            .baselineVersion(baselineVersion)
            .load()

        /** Flyway writes a baseline row or an applied file into the history, so an empty one is none. */
        private fun Flyway.hasHistory(): Boolean = info().applied().isNotEmpty()

        private fun parseVersion(version: String): MigrationVersion? =
            runCatching { MigrationVersion.fromVersion(version) }.getOrNull()
    }
}
