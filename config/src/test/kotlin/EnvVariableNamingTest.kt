package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The documented environment variable convention must be the convention that works.
 *
 * `EnvConfigLoader` maps a single underscore to a path separator, and
 * `EnvConfigLoader.yamlPathToEnvKey` builds every name that a validation error prints. Section 9
 * of `hardening-doc.md` names `QUEUEBOX_DATABASE_URL` in finding F-073. A user who follows any of
 * those must configure the application.
 */
class EnvVariableNamingTest {

    @Test
    fun `a single underscore separates the path segments`() {
        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { mapOf("QUEUEBOX_SERVER_HTTPPORT" to "7777") }
        )

        assertEquals(7777, config.server.httpPort)
    }

    @Test
    fun `the name that a validation error prints is the name that binds`() {
        val url = "jdbc:postgresql://elsewhere:5432/other"
        val key = EnvConfigLoader.yamlPathToEnvKey("database.url")

        val config = ConfigLoader.loadAuto(path = "test-config.yml", env = { mapOf(key to url) })

        assertEquals(url, config.database.url)
    }

    @Test
    fun `a camel case leaf binds from the flattened upper case name`() {
        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { mapOf("QUEUEBOX_OUTBOX_BATCHSIZE" to "42") }
        )

        assertEquals(42, config.outbox.batchSize)
    }
}
