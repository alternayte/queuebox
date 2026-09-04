package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvConfigLoaderTest {

    @Test
    fun `envKeyToYamlPath should transform simple keys correctly`() {
        assertEquals("database.url", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE_URL"))
        assertEquals("database.username", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE_USERNAME"))
        assertEquals("database.password", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE_PASSWORD"))
    }

    @Test
    fun `envKeyToYamlPath should handle nested paths`() {
        assertEquals("server.http.port", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_SERVER_HTTP_PORT"))
        assertEquals("outbox.poll.interval.ms", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_OUTBOX_POLL_INTERVAL_MS"))
    }

    @Test
    fun `envKeyToYamlPath should handle double underscore as literal underscore`() {
        // Double underscore escapes to literal underscore in the path
        assertEquals("database_pool.size", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE__POOL_SIZE"))
        assertEquals("my_app.config", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_MY__APP_CONFIG"))
    }

    @Test
    fun `envKeyToYamlPath should handle array indexing notation`() {
        // Hoplite handles array indexing with numeric segments
        assertEquals("routes.0.topic.pattern", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_ROUTES_0_TOPIC_PATTERN"))
        assertEquals("routes.1.destination", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_ROUTES_1_DESTINATION"))
        assertEquals(
            "destinations.http1.base.url",
            EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DESTINATIONS_HTTP1_BASE_URL")
        )
    }

    @Test
    fun `yamlPathToEnvKey should transform simple paths correctly`() {
        assertEquals("QUEUEBOX_DATABASE_URL", EnvConfigLoader.yamlPathToEnvKey("database.url"))
        assertEquals("QUEUEBOX_DATABASE_USERNAME", EnvConfigLoader.yamlPathToEnvKey("database.username"))
        assertEquals("QUEUEBOX_SERVER_HTTPPORT", EnvConfigLoader.yamlPathToEnvKey("server.httpPort"))
    }

    @Test
    fun `yamlPathToEnvKey should escape literal underscores with double underscore`() {
        assertEquals("QUEUEBOX_DATABASE__POOL_SIZE", EnvConfigLoader.yamlPathToEnvKey("database_pool.size"))
        assertEquals("QUEUEBOX_MY__APP_CONFIG", EnvConfigLoader.yamlPathToEnvKey("my_app.config"))
    }

    @Test
    fun `loadFromEnv should filter only QUEUEBOX prefixed variables`() {
        val mockEnv = mapOf(
            "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://localhost:5432/test",
            "QUEUEBOX_DATABASE_USERNAME" to "user",
            "PATH" to "/usr/bin",
            "HOME" to "/home/user",
            "OTHER_VAR" to "value"
        )

        val result = EnvConfigLoader.loadFromEnv { mockEnv }

        assertEquals(2, result.size)
        assertEquals("jdbc:postgresql://localhost:5432/test", result["database.url"])
        assertEquals("user", result["database.username"])
        assertFalse(result.containsKey("PATH"))
        assertFalse(result.containsKey("HOME"))
    }

    @Test
    fun `loadFromEnv should preserve empty string values`() {
        val mockEnv = mapOf(
            "QUEUEBOX_DATABASE_URL" to "",
            "QUEUEBOX_EMPTY_VALUE" to ""
        )

        val result = EnvConfigLoader.loadFromEnv { mockEnv }

        assertEquals(2, result.size)
        assertEquals("", result["database.url"])
        assertEquals("", result["empty.value"])
    }

    @Test
    fun `loadFromEnv should return empty map when no QUEUEBOX vars present`() {
        val mockEnv = mapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/home/user"
        )

        val result = EnvConfigLoader.loadFromEnv { mockEnv }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getQueueBoxEnvKeys should return only QUEUEBOX keys`() {
        val mockEnv = mapOf(
            "QUEUEBOX_DATABASE_URL" to "value1",
            "QUEUEBOX_SERVER_PORT" to "value2",
            "PATH" to "/usr/bin"
        )

        val result = EnvConfigLoader.getQueueBoxEnvKeys { mockEnv }

        assertEquals(setOf("QUEUEBOX_DATABASE_URL", "QUEUEBOX_SERVER_PORT"), result)
    }

    @Test
    fun `hasEnvConfig should return true when QUEUEBOX vars exist`() {
        val mockEnv = mapOf(
            "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://localhost:5432/test"
        )

        assertTrue(EnvConfigLoader.hasEnvConfig { mockEnv })
    }

    @Test
    fun `hasEnvConfig should return false when no QUEUEBOX vars exist`() {
        val mockEnv = mapOf(
            "PATH" to "/usr/bin"
        )

        assertFalse(EnvConfigLoader.hasEnvConfig { mockEnv })
    }

    @Test
    fun `roundtrip conversion should work correctly`() {
        val originalPath = "database.url"
        val envKey = EnvConfigLoader.yamlPathToEnvKey(originalPath)
        val backToPath = EnvConfigLoader.envKeyToYamlPath(envKey)

        assertEquals(originalPath, backToPath)
    }

    @Test
    fun `roundtrip conversion with literal underscores should work correctly`() {
        val originalPath = "my_custom.setting"
        val envKey = EnvConfigLoader.yamlPathToEnvKey(originalPath)
        val backToPath = EnvConfigLoader.envKeyToYamlPath(envKey)

        assertEquals(originalPath, backToPath)
    }
}
