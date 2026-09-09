package org.nxtspec.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers F-092. A unit test on [org.nxtspec.StartupValidator] alone proves only that the
 * validator works. It proves nothing about the real startup, because nothing forces
 * [org.nxtspec.StartupValidator.validateAddressTemplates] to run there. This test calls [runApp]
 * with a fake environment, the same entry point that `main` calls with the real one, so a
 * validator that is written but never wired into the startup makes this test fail.
 */
class StartupAddressTemplateValidationTest {

    @Test
    fun `the real startup fails on a destination with an unknown template field`() {
        val configFile = File.createTempFile("queuebox-bad-template", ".yml")
        configFile.deleteOnExit()
        configFile.writeText(
            """
            database:
              url: jdbc:postgresql://localhost:5432/queuebox
              username: postgres
              password: secret

            destinations:
              events:
                type: rabbitmq
                url: amqp://guest:guest@localhost:5672
                exchange: "public.{{ nosuchfield }}.v1"
            """.trimIndent()
        )

        val env = { mapOf(CONFIG_LOADER_CONFIG_FILE_ENV_KEY to configFile.absolutePath) }

        val error = assertFailsWith<StartupFailedException> {
            runApp(env)
        }

        assertTrue(error.message!!.contains("nosuchfield"), "the failure must name the offending field")
        assertTrue(error.message!!.contains("events"), "the failure must name the offending destination")
    }
}

/** The environment variable that names an external configuration file. See `ConfigLoader`. */
private const val CONFIG_LOADER_CONFIG_FILE_ENV_KEY = "QUEUEBOX_CONFIG_FILE"
