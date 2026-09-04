package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the external configuration file source.
 *
 * The precedence is:
 * 1. A QUEUEBOX_* environment variable.
 * 2. The file named by QUEUEBOX_CONFIG_FILE.
 * 3. The default external path /etc/queuebox/queuebox.yml.
 * 4. The packaged classpath resource.
 */
class ExternalConfigFileTest {

    private fun writeExternalFile(httpPort: Int): File {
        val file = File.createTempFile("queuebox-external", ".yml")
        file.deleteOnExit()
        file.writeText(
            """
            server:
              httpPort: $httpPort
            """.trimIndent()
        )
        return file
    }

    @Test
    fun `external file named by QUEUEBOX_CONFIG_FILE wins over the packaged resource`() {
        val file = writeExternalFile(9099)

        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) }
        )

        assertEquals(9099, config.server.httpPort)
        assertEquals("jdbc:postgresql://localhost:5432/queuebox", config.database.url)
    }

    @Test
    fun `packaged resource loads when no external file is present`() {
        val config = ConfigLoader.loadAuto(
            path = "test-config.yml",
            env = { emptyMap() }
        )

        assertEquals(8080, config.server.httpPort)
        assertEquals("jdbc:postgresql://localhost:5432/queuebox", config.database.url)
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
}
