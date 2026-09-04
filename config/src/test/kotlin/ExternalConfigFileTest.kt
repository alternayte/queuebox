package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the external configuration file source. See finding F-076.
 *
 * The order is:
 * 1. A QUEUEBOX_* environment variable, which always wins.
 * 2. The file named by QUEUEBOX_CONFIG_FILE, or /etc/queuebox/queuebox.yml when the environment
 *    names no other path.
 * 3. The packaged classpath resource, but only when no external file exists.
 *
 * An external file REPLACES the packaged resource. It does not overlay it. An external file is
 * therefore a complete configuration.
 */
class ExternalConfigFileTest {

    /** A complete configuration, because an external file replaces the packaged resource. */
    private fun writeExternalFile(httpPort: Int, databaseUrl: String = EXTERNAL_URL): File {
        val file = File.createTempFile("queuebox-external", ".yml")
        file.deleteOnExit()
        file.writeText(
            """
            server:
              httpPort: $httpPort
            database:
              url: $databaseUrl
              username: external-user
              password: external-password
            """.trimIndent()
        )
        return file
    }

    private companion object {
        const val EXTERNAL_URL = "jdbc:postgresql://external-host:5432/external"
        const val PACKAGED_URL = "jdbc:postgresql://localhost:5432/queuebox"
    }

    @Test
    fun `external file named by QUEUEBOX_CONFIG_FILE wins over the packaged resource`() {
        val file = writeExternalFile(9099)

        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) }
        )

        assertEquals(9099, config.server.httpPort)
        // The external file replaces the packaged resource, so no packaged value survives.
        assertEquals(EXTERNAL_URL, config.database.url)
    }

    @Test
    fun `packaged resource loads when no external file is present`() {
        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { emptyMap() }
        )

        assertEquals(8080, config.server.httpPort)
        assertEquals(PACKAGED_URL, config.database.url)
    }

    @Test
    fun `environment variable wins over the external file`() {
        val file = writeExternalFile(9099)

        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = {
                mapOf(
                    "QUEUEBOX_CONFIG_FILE" to file.absolutePath,
                    "QUEUEBOX_SERVER_HTTPPORT" to "7777"
                )
            }
        )

        assertEquals(7777, config.server.httpPort)
    }

    /**
     * F-076: the packaged resource is a fallback, not an overlay.
     *
     * Hoplite cascades a map node key by key. So an external file that declares one destination
     * and one source used to inherit every destination and every source of the packaged resource,
     * including the `github` and `stripe` inbox endpoints. A deployment then served an HTTP
     * endpoint that its own configuration never declared.
     */
    @Test
    fun `an external file replaces the packaged resource instead of merging with it`() {
        val file = File.createTempFile("queuebox-complete", ".yml")
        file.deleteOnExit()
        file.writeText(
            """
            server:
              httpPort: 8080
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: u
              password: p
            destinations:
              only-mine:
                type: http
                baseUrl: http://example.invalid
                path: /x
            routes:
              - topicPattern: "**"
                destination: only-mine
            sources:
              only-my-source:
                path: /mine
                idempotencyKeyPath: ${'$'}.id
                eventTypePath: ${'$'}.type
            """.trimIndent()
        )

        val config = ConfigLoader.load(
            path = "test-config.yml",
            env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) }
        )

        assertEquals(setOf("only-mine"), config.destinations.keys)
        assertEquals(setOf("only-my-source"), config.sources.keys)
    }
}
