package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The documented environment variable convention must be the convention that works.
 *
 * `EnvConfigLoader` maps a single underscore to a path separator, and
 * `EnvConfigLoader.yamlPathToEnvKey` builds every name that a validation error prints. Section 9
 * of `hardening-doc.md` names `QUEUEBOX_DATABASE_URL` in finding F-073. A user who follows any of
 * those must configure the application.
 *
 * Each test overrides one value of a configuration file, because a variable that overrides a file
 * is the supported deployment shape. The packaged classpath resource is a fallback for a run that
 * sets no variable at all, so it cannot serve as the base here.
 */
class EnvVariableNamingTest {

    /** A complete configuration on disk, for a variable to override one value of. */
    private fun baseFile(): File {
        val file = File.createTempFile("queuebox-naming", ".yml")
        file.deleteOnExit()
        file.writeText(
            """
            server:
              httpPort: 8080
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret
            outbox:
              batchSize: 100
            """.trimIndent()
        )
        return file
    }

    @Test
    fun `a single underscore separates the path segments`() {
        val config = ConfigLoader.load(
            env = {
                mapOf(
                    "QUEUEBOX_CONFIG_FILE" to baseFile().absolutePath,
                    "QUEUEBOX_SERVER_HTTPPORT" to "7777"
                )
            }
        )

        assertEquals(7777, config.server.httpPort)
    }

    @Test
    fun `the name that a validation error prints is the name that binds`() {
        val url = "jdbc:postgresql://elsewhere:5432/other"
        val key = EnvConfigLoader.yamlPathToEnvKey("database.url")

        val config = ConfigLoader.load(
            env = { mapOf("QUEUEBOX_CONFIG_FILE" to baseFile().absolutePath, key to url) }
        )

        assertEquals(url, config.database.url)
    }

    @Test
    fun `a camel case leaf binds from the flattened upper case name`() {
        val config = ConfigLoader.load(
            env = {
                mapOf(
                    "QUEUEBOX_CONFIG_FILE" to baseFile().absolutePath,
                    "QUEUEBOX_OUTBOX_BATCHSIZE" to "42"
                )
            }
        )

        assertEquals(42, config.outbox.batchSize)
    }
}
