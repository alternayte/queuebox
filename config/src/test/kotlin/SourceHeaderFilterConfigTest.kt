package org.nxtspec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceHeaderFilterConfigTest {

    private val expected = HeaderFilterConfig(
        require = listOf(
            HeaderRule("x-tenant", equals = "acme"),
            HeaderRule("x-region", `in` = listOf("eu", "us"))
        ),
        exclude = listOf(
            HeaderRule("x-event", matches = "test.**"),
            HeaderRule("x-debug", exists = true)
        )
    )

    private fun yamlFile(filterYaml: String): File = File.createTempFile("queuebox-filter", ".yml").apply {
        deleteOnExit()
        writeText(
            """
            |database:
            |  url: jdbc:postgresql://db:5432/app
            |  username: app
            |  password: secret
            |sources:
            |  stripe:
            |    type: http
            |    path: /stripe
            |    idempotencyKeyPath: ${'$'}.id
            |    topic: "{{ source }}"
            |    filter:
            |$filterYaml
            """.trimMargin()
        )
    }

    @Test
    fun `a filter set in YAML and the same filter set in variables bind to the same rules`() {
        val file = yamlFile(
            """
            |      require:
            |        - header: x-tenant
            |          equals: acme
            |        - header: x-region
            |          in: [eu, us]
            |      exclude:
            |        - header: x-event
            |          matches: "test.**"
            |        - header: x-debug
            |          exists: true
            """.trimMargin()
        )
        val fromYaml = ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) })

        val fromEnv = ConfigLoader.load(
            env = {
                mapOf(
                    "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/app",
                    "QUEUEBOX_DATABASE_USERNAME" to "app",
                    "QUEUEBOX_DATABASE_PASSWORD" to "secret",
                    "QUEUEBOX_SOURCES_STRIPE_TYPE" to "http",
                    "QUEUEBOX_SOURCES_STRIPE_PATH" to "/stripe",
                    "QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH" to "\$.id",
                    "QUEUEBOX_SOURCES_STRIPE_TOPIC" to "{{ source }}",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_REQUIRE_0_HEADER" to "x-tenant",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_REQUIRE_0_EQUALS" to "acme",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_REQUIRE_1_HEADER" to "x-region",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_REQUIRE_1_IN_0" to "eu",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_REQUIRE_1_IN_1" to "us",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_EXCLUDE_0_HEADER" to "x-event",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_EXCLUDE_0_MATCHES" to "test.**",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_EXCLUDE_1_HEADER" to "x-debug",
                    "QUEUEBOX_SOURCES_STRIPE_FILTER_EXCLUDE_1_EXISTS" to "true"
                )
            }
        )

        assertEquals(expected, fromYaml.sources.getValue("stripe").filter)
        assertEquals(expected, fromEnv.sources.getValue("stripe").filter)
    }

    @Test
    fun `a rule with two tests stops the start and names the rule`() {
        val file = yamlFile(
            """
            |      require:
            |        - header: x-tenant
            |          equals: acme
            |          exists: true
            """.trimMargin()
        )

        val error = assertFailsWith<Exception> {
            ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) })
        }

        assertTrue(error.message!!.contains("sources.stripe.filter.require.0"), error.message)
    }
}
