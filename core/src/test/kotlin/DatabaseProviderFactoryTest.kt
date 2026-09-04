package org.nxtspec

import org.nxtspec.repository.ColumnMappingData
import org.nxtspec.repository.DatabaseProviderFactory
import org.nxtspec.repository.DatabaseType
import org.nxtspec.repository.MissingDatabaseProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F-080. The factory resolves the provider class by reflection. When the provider module is absent
 * the caller must get a named error that names the Gradle module to add.
 */
class DatabaseProviderFactoryTest {

    /** A class loader that hides every QueueBox provider class. */
    private class ProviderBlindClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.endsWith("RepositoryFactory")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    private val blindLoader = ProviderBlindClassLoader(this::class.java.classLoader)

    @Test
    fun `an absent postgres provider gives a named error that names the module`() {
        val error = assertFailsWith<MissingDatabaseProviderException> {
            DatabaseProviderFactory.create(
                DatabaseType.POSTGRESQL,
                NoDataSource,
                ColumnMappingData(),
                blindLoader
            )
        }
        assertEquals("postgres", error.module)
        val message = error.message.orEmpty()
        assertTrue(message.contains("postgres"), "The message must name the module: $message")
        assertTrue(
            message.contains("org.nxtspec.PostgresRepositoryFactory"),
            "The message must name the class that is absent: $message"
        )
    }

    @Test
    fun `an absent sqlserver provider gives a named error that names the module`() {
        val error = assertFailsWith<MissingDatabaseProviderException> {
            DatabaseProviderFactory.create(
                DatabaseType.SQLSERVER,
                NoDataSource,
                ColumnMappingData(),
                blindLoader
            )
        }
        assertEquals("sqlserver", error.module)
        assertTrue(
            error.message.orEmpty().contains("sqlserver"),
            "The message must name the module: ${error.message}"
        )
    }

    @Test
    fun `the named error keeps the class not found cause`() {
        val error = assertFailsWith<MissingDatabaseProviderException> {
            DatabaseProviderFactory.create(
                DatabaseType.POSTGRESQL,
                NoDataSource,
                ColumnMappingData(),
                blindLoader
            )
        }
        assertTrue(error.cause is ClassNotFoundException, "The cause must be the reflection failure.")
    }
}
