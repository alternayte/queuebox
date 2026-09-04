package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-031. The PostgreSQL migration set and the SQL Server migration set must hold the same
 * version numbers. The test reads both resource directories at run time, so a migration that
 * one dialect gains and the other misses breaks the build.
 */
class MigrationParityTest {

    private val repositoryRoot: File = findRepositoryRoot()

    private val postgresDirectory = File(repositoryRoot, "postgres/src/main/resources/db/postgresql")

    private val sqlServerDirectory = File(repositoryRoot, "sqlserver/src/main/resources/db/sqlserver")

    @Test
    fun `both dialects hold the same migration versions`() {
        val postgresVersions = versionsOf(postgresDirectory)
        val sqlServerVersions = versionsOf(sqlServerDirectory)

        val missingInSqlServer = postgresVersions.keys - sqlServerVersions.keys
        val missingInPostgres = sqlServerVersions.keys - postgresVersions.keys

        assertTrue(
            missingInSqlServer.isEmpty(),
            "The SQL Server migrations miss the versions " +
                "${missingInSqlServer.sorted()} that PostgreSQL holds as " +
                "${missingInSqlServer.sorted().map { postgresVersions.getValue(it) }}."
        )
        assertTrue(
            missingInPostgres.isEmpty(),
            "The PostgreSQL migrations miss the versions " +
                "${missingInPostgres.sorted()} that SQL Server holds as " +
                "${missingInPostgres.sorted().map { sqlServerVersions.getValue(it) }}."
        )
        assertEquals(
            postgresVersions.keys,
            sqlServerVersions.keys,
            "The two migration sets differ."
        )
    }

    @Test
    fun `each dialect holds a contiguous version sequence that starts at one`() {
        val postgresVersions = versionsOf(postgresDirectory).keys.sorted()
        assertTrue(postgresVersions.isNotEmpty(), "No PostgreSQL migration was found.")
        assertEquals(
            (1..postgresVersions.size).toList(),
            postgresVersions,
            "The PostgreSQL migration versions have a gap or do not start at one."
        )
    }

    /** Maps the version number of every migration in [directory] to the file name. */
    private fun versionsOf(directory: File): Map<Int, String> {
        assertTrue(directory.isDirectory, "The migration directory ${directory.path} does not exist.")
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".sql") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(files.isNotEmpty(), "The migration directory ${directory.path} holds no SQL file.")
        val versions = mutableMapOf<Int, String>()
        files.forEach { file ->
            val match = VERSION_PATTERN.find(file.name)
            assertTrue(
                match != null,
                "The migration ${file.name} in ${directory.path} does not follow the name form V<number>__<name>.sql."
            )
            val version = match.groupValues[1].toInt()
            val previous = versions.put(version, file.name)
            assertTrue(
                previous == null,
                "The version $version is used twice in ${directory.path}: $previous and ${file.name}."
            )
        }
        return versions
    }

    private fun findRepositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("The repository root was not found from ${File(".").absolutePath}.")
    }

    private companion object {
        val VERSION_PATTERN = Regex("""^V(\d+)__.+\.sql$""")
    }
}
