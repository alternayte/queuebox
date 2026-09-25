package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #63. The validator tells the user to write `signaturePayloadFormat: 'timestamp-dot-body'`,
 * and the enum declares that serial name, so the loader must accept it. Every earlier release
 * accepted the enum constant names, so those spellings must keep loading.
 */
class SignaturePayloadFormatLoadTest {

    private fun writeConfig(format: String): File {
        val file = File.createTempFile("queuebox-hmac-format", ".yml")
        file.deleteOnExit()
        file.writeText(
            """
            database:
              url: jdbc:postgresql://db:5432/app
              username: app
              password: secret
            sources:
              stripe:
                type: http
                path: /stripe
                idempotencyKeyPath: ${'$'}.id
                eventTypePath: ${'$'}.type
                auth:
                  type: hmac
                  secret: source-secret
                  timestampHeader: X-Timestamp
                  signaturePayloadFormat: $format
            admin:
              enabled: true
              auth:
                type: hmac
                secret: admin-secret
                timestampHeader: X-Timestamp
                signaturePayloadFormat: $format
            """.trimIndent()
        )
        return file
    }

    private fun loadYaml(format: String): QueueBoxConfig {
        val file = writeConfig(format)
        return ConfigLoader.load(env = { mapOf(ConfigLoader.CONFIG_FILE_ENV to file.absolutePath) })
    }

    private fun loadEnv(format: String): QueueBoxConfig = ConfigLoader.load(
        env = {
            mapOf(
                "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/app",
                "QUEUEBOX_DATABASE_USERNAME" to "app",
                "QUEUEBOX_DATABASE_PASSWORD" to "secret",
                "QUEUEBOX_SOURCES_STRIPE_TYPE" to "http",
                "QUEUEBOX_SOURCES_STRIPE_PATH" to "/stripe",
                "QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH" to "\$.id",
                "QUEUEBOX_SOURCES_STRIPE_EVENTTYPEPATH" to "\$.type",
                "QUEUEBOX_SOURCES_STRIPE_AUTH_TYPE" to "hmac",
                "QUEUEBOX_SOURCES_STRIPE_AUTH_SECRET" to "source-secret",
                "QUEUEBOX_SOURCES_STRIPE_AUTH_TIMESTAMPHEADER" to "X-Timestamp",
                "QUEUEBOX_SOURCES_STRIPE_AUTH_SIGNATUREPAYLOADFORMAT" to format,
                "QUEUEBOX_ADMIN_ENABLED" to "true",
                "QUEUEBOX_ADMIN_AUTH_TYPE" to "hmac",
                "QUEUEBOX_ADMIN_AUTH_SECRET" to "admin-secret",
                "QUEUEBOX_ADMIN_AUTH_TIMESTAMPHEADER" to "X-Timestamp",
                "QUEUEBOX_ADMIN_AUTH_SIGNATUREPAYLOADFORMAT" to format
            )
        }
    )

    private fun assertFormat(expected: SignaturePayloadFormat, config: QueueBoxConfig) {
        val source = (config.sources.getValue("stripe") as SourceConfig.Http).auth
        assertEquals(expected, (source as InboxAuthConfig.HmacSignature).signaturePayloadFormat)
        val admin = config.admin.auth
        assertEquals(expected, (admin as InboxAuthConfig.HmacSignature).signaturePayloadFormat)
    }

    @Test
    fun `the documented spellings load from a YAML file`() {
        assertFormat(SignaturePayloadFormat.TIMESTAMP_DOT_BODY, loadYaml("timestamp-dot-body"))
        assertFormat(SignaturePayloadFormat.BODY, loadYaml("body"))
    }

    @Test
    fun `the documented spellings load from QUEUEBOX_ variables`() {
        assertFormat(SignaturePayloadFormat.TIMESTAMP_DOT_BODY, loadEnv("timestamp-dot-body"))
        assertFormat(SignaturePayloadFormat.BODY, loadEnv("body"))
    }

    @Test
    fun `the enum constant names that earlier releases accepted still load`() {
        assertFormat(SignaturePayloadFormat.TIMESTAMP_DOT_BODY, loadYaml("TIMESTAMP_DOT_BODY"))
        assertFormat(SignaturePayloadFormat.BODY, loadYaml("BODY"))
        assertFormat(SignaturePayloadFormat.TIMESTAMP_DOT_BODY, loadEnv("TIMESTAMP_DOT_BODY"))
        assertFormat(SignaturePayloadFormat.BODY, loadEnv("BODY"))
    }
}
