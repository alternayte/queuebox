package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Issue #80. See `docs/specs/header-keys.md`. */
class HttpSourceHeaderKeysConfigTest {

    private val database = """
        |database:
        |  url: jdbc:postgresql://db:5432/app
        |  username: app
        |  password: secret
    """.trimMargin()

    private fun load(sourceYaml: String): QueueBoxConfig {
        val file = File.createTempFile("queuebox-header-keys", ".yml").apply {
            deleteOnExit()
            writeText("$database\nsources:\n  github:\n$sourceYaml\n")
        }
        return ConfigLoader.load(env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) })
    }

    private fun loadEnv(vararg source: Pair<String, String>): QueueBoxConfig = ConfigLoader.load(
        env = {
            mapOf(
                "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://db:5432/app",
                "QUEUEBOX_DATABASE_USERNAME" to "app",
                "QUEUEBOX_DATABASE_PASSWORD" to "secret"
            ) + source
        }
    )

    @Test
    fun `the three header keys load from YAML and from variables to the same source`() {
        val fromYaml = load(
            """
            |    type: http
            |    path: /github
            |    idempotencyKeyHeader: X-GitHub-Delivery
            |    eventTypeHeader: X-GitHub-Event
            |    aggregateIdHeader: X-GitHub-Hook-ID
            |    aggregateIdPath: ${'$'}.repository.full_name
            |    auth:
            |      type: hmac
            |      secret: s3cret
            |      headerName: X-Hub-Signature-256
            |      signaturePrefix: "sha256="
            """.trimMargin()
        )
        val fromEnv = loadEnv(
            "QUEUEBOX_SOURCES_GITHUB_TYPE" to "http",
            "QUEUEBOX_SOURCES_GITHUB_PATH" to "/github",
            "QUEUEBOX_SOURCES_GITHUB_IDEMPOTENCYKEYHEADER" to "X-GitHub-Delivery",
            "QUEUEBOX_SOURCES_GITHUB_EVENTTYPEHEADER" to "X-GitHub-Event",
            "QUEUEBOX_SOURCES_GITHUB_AGGREGATEIDHEADER" to "X-GitHub-Hook-ID",
            "QUEUEBOX_SOURCES_GITHUB_AGGREGATEIDPATH" to "\$.repository.full_name",
            "QUEUEBOX_SOURCES_GITHUB_AUTH_TYPE" to "hmac",
            "QUEUEBOX_SOURCES_GITHUB_AUTH_SECRET" to "s3cret",
            "QUEUEBOX_SOURCES_GITHUB_AUTH_HEADERNAME" to "X-Hub-Signature-256",
            "QUEUEBOX_SOURCES_GITHUB_AUTH_SIGNATUREPREFIX" to "sha256="
        )

        val source = fromYaml.sources.getValue("github") as SourceConfig.Http
        assertEquals("X-GitHub-Delivery", source.idempotencyKeyHeader)
        assertEquals("X-GitHub-Event", source.eventTypeHeader)
        assertEquals("X-GitHub-Hook-ID", source.aggregateIdHeader)
        assertEquals("\$.repository.full_name", source.aggregateIdPath)
        assertNull(source.idempotencyKeyPath)
        assertEquals(fromYaml.sources, fromEnv.sources)
    }

    @Test
    fun `the strict check knows the header keys and suggests one for a near miss`() {
        val error = assertFailsWith<ConfigException> {
            load(
                """
                |    path: /github
                |    idempotencyKeyHeadr: X-GitHub-Delivery
                |    topic: "{{ source }}"
                """.trimMargin()
            )
        }
        assertContains(error.message!!, "sources.github.idempotencyKeyHeadr: unknown key for type http")
        assertContains(error.message!!, "did you mean idempotencyKeyHeader?")
    }

    @Test
    fun `a source with neither a key path nor a key header stops the start and names both`() {
        val error = assertFailsWith<IllegalArgumentException> {
            load(
                """
                |    path: /github
                |    topic: "{{ source }}"
                """.trimMargin()
            )
        }
        assertContains(error.message!!, "sources.github.idempotencyKeyPath")
        assertContains(error.message!!, "sources.github.idempotencyKeyHeader")
    }

    @Test
    fun `an event type header satisfies a topic that uses eventType`() {
        val config = load(
            """
            |    path: /github
            |    idempotencyKeyHeader: X-GitHub-Delivery
            |    eventTypeHeader: X-GitHub-Event
            """.trimMargin()
        )
        assertEquals("{{ eventType }}", config.sources.getValue("github").topic)
    }

    @Test
    fun `a key header that the hmac auth reads stops the start with a clear error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            load(
                """
                |    path: /github
                |    idempotencyKeyHeader: x-hub-signature-256
                |    topic: "{{ source }}"
                |    auth:
                |      type: hmac
                |      secret: s3cret
                |      headerName: X-Hub-Signature-256
                """.trimMargin()
            )
        }
        assertContains(error.message!!, "Source 'github' idempotencyKeyHeader 'x-hub-signature-256'")
        assertContains(error.message!!, "'sources.github.auth.headerName' names it")
    }

    @Test
    fun `a key header that the api-key auth reads by default stops the start`() {
        val error = assertFailsWith<IllegalArgumentException> {
            load(
                """
                |    path: /github
                |    idempotencyKeyPath: ${'$'}.id
                |    aggregateIdHeader: X-API-Key
                |    topic: "{{ source }}"
                |    auth:
                |      type: api-key
                |      key: k
                """.trimMargin()
            )
        }
        assertContains(error.message!!, "aggregateIdHeader 'X-API-Key'")
    }

    @Test
    fun `Authorization, Proxy-Authorization and Cookie cannot be key headers`() {
        listOf("Authorization", "proxy-authorization", "COOKIE").forEach { header ->
            val error = assertFailsWith<IllegalArgumentException>(header) {
                load(
                    """
                    |    path: /github
                    |    idempotencyKeyPath: ${'$'}.id
                    |    eventTypeHeader: $header
                    """.trimMargin()
                )
            }
            assertContains(error.message!!, "eventTypeHeader '$header' names a credential header")
            assertContains(error.message!!, "QueueBox never stores it")
        }
    }

    @Test
    fun `a blank key header stops the start`() {
        val error = assertFailsWith<IllegalArgumentException> {
            load(
                """
                |    path: /github
                |    idempotencyKeyHeader: "  "
                |    topic: "{{ source }}"
                """.trimMargin()
            )
        }
        assertContains(error.message!!, "Source 'github' idempotencyKeyHeader cannot be blank")
    }
}
