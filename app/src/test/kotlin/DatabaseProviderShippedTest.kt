package org.nxtspec.app

import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Tenth review gate B3. Every documented database provider must be ON the shipped class path.
 *
 * `:sqlserver` was a test dependency only, so the published image held no SQL Server provider and
 * no driver. `DatabaseProviderFactory` loads a provider by reflection, so `type: sqlserver` threw
 * `MissingDatabaseProviderException` at startup, while `README.md`, `docs/configuration.md` and
 * `docs/getting-started.md` all promised the support. Reflection hides that kind of break from the
 * compiler, so a test has to hold it.
 */
class DatabaseProviderShippedTest {

    @Test
    fun `every documented database type resolves its provider class`() {
        for (type in DatabaseType.entries) {
            val className = when (type) {
                DatabaseType.POSTGRESQL -> "org.nxtspec.PostgresRepositoryFactory"
                DatabaseType.SQLSERVER -> "org.nxtspec.SqlServerRepositoryFactory"
            }
            val loaded = runCatching { Class.forName(className) }.getOrNull()
            assertNotNull(
                loaded,
                "$className is not on the class path, so `type: ${type.name.lowercase()}` " +
                    "cannot start. Either ship the module or remove the claim from the documents."
            )
        }
    }

    @Test
    fun `every documented database type has a jdbc driver on the class path`() {
        for (driver in listOf(
            "org.postgresql.Driver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        )) {
            val loaded = runCatching { Class.forName(driver) }.getOrNull()
            assertNotNull(loaded, "$driver is not on the class path, so its database cannot connect")
        }
    }

    @Test
    fun `the factory is reachable for every type`() {
        // A compile-time reference, so a rename of the enum breaks the build rather than a run.
        assertNotNull(DatabaseProviderFactory)
    }
}
