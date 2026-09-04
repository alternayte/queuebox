package org.nxtspec

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers F-038 and F-045. A loaded configuration must print no credential, and a `file:`
 * reference must resolve.
 */
class ConfigSecretTest {

    /** Every credential value that `secrets-config.yml` sets. */
    private val secretValues = listOf(
        "db-password-VALUE",
        "oauth-secret-VALUE",
        "basic-password-VALUE",
        "header-token-VALUE",
        "bearer-token-VALUE",
        "api-key-VALUE",
        "hmac-secret-VALUE"
    )

    @Test
    fun `the configuration toString prints no credential`() {
        val config = ConfigLoader.load("secrets-config.yml")

        val printed = config.toString()

        secretValues.forEach { value ->
            assertFalse(printed.contains(value), "toString must not print '$value'")
        }
        assertTrue(printed.contains(Secret.MASK), "A credential must print as the mask")
    }

    @Test
    fun `every credential field carries a Secret`() {
        val config = ConfigLoader.load("secrets-config.yml")

        assertEquals("db-password-VALUE", config.database.password.reveal())

        val oauth = (config.destinations["oauth-api"] as DestinationConfig.Http).auth
        assertEquals(
            "oauth-secret-VALUE",
            (oauth as DestinationAuthConfig.OAuth2).clientSecret.reveal()
        )

        val basic = (config.destinations["basic-api"] as DestinationConfig.Http).auth
        assertEquals(
            "basic-password-VALUE",
            (basic as DestinationAuthConfig.Basic).password.reveal()
        )

        val header = (config.destinations["header-api"] as DestinationConfig.Http).auth
        assertEquals(
            "header-token-VALUE",
            (header as DestinationAuthConfig.Header).headerValue.reveal()
        )

        val bearer = (config.sources["bearer-source"] as SourceConfig.Http).auth
        assertEquals("bearer-token-VALUE", (bearer as InboxAuthConfig.Bearer).token.reveal())

        val apiKey = (config.sources["apikey-source"] as SourceConfig.Http).auth
        assertEquals("api-key-VALUE", (apiKey as InboxAuthConfig.ApiKey).key.reveal())

        val hmac = (config.sources["hmac-source"] as SourceConfig.Http).auth
        assertEquals("hmac-secret-VALUE", (hmac as InboxAuthConfig.HmacSignature).secret.reveal())
    }

    @Test
    fun `the toString of every enclosing object prints no credential`() {
        val config = ConfigLoader.load("secrets-config.yml")

        val printed = listOf(
            config.database.toString(),
            config.destinations.toString(),
            config.sources.toString()
        ).joinToString(" ")

        secretValues.forEach { value ->
            assertFalse(printed.contains(value), "An enclosing toString must not print '$value'")
        }
    }

    @Test
    fun `a file reference resolves through the loader and never prints the content`() {
        // The YAML points at this fixed path. The Gradle test working directory is the module
        // directory, so the relative path is stable.
        val file = java.io.File("build/tmp/queuebox-test-secret.txt")
        file.parentFile.mkdirs()
        file.writeText("password-from-file-VALUE\n")

        try {
            val config = ConfigLoader.load("file-secret-config.yml")

            assertEquals("password-from-file-VALUE", config.database.password.reveal())
            assertFalse(config.toString().contains("password-from-file-VALUE"))
            assertTrue(config.database.toString().contains(Secret.MASK))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the loader fails when the secret file is missing`() {
        val file = java.io.File("build/tmp/queuebox-test-secret.txt")
        file.delete()

        val exception = assertFailsWith<Exception> {
            ConfigLoader.load("file-secret-config.yml")
        }

        assertTrue(
            exception.message!!.contains("queuebox-test-secret.txt"),
            "The failure must name the path: ${exception.message}"
        )
    }
}
