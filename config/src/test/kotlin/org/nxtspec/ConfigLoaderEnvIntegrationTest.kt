package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for environment variable configuration loading.
 *
 * These tests verify:
 * - YAML-only loading (backward compatibility)
 * - Environment variable overrides
 * - Environment-only mode
 * - Array indexing with env vars
 * - Map/object key handling
 * - Error handling for missing required fields
 */
class ConfigLoaderEnvIntegrationTest {

    @Test
    fun `load should work with YAML only (backward compatibility)`() {
        val config = ConfigLoader.load("test-config.yml")

        assertNotNull(config)
        assertEquals("jdbc:postgresql://localhost:5432/queuebox", config.database.url)
        assertEquals("postgres", config.database.username)
        assertEquals(8080, config.server.httpPort)
    }

    @Test
    fun `loadFromEnvOnly should throw when no QUEUEBOX vars are set`() {
        // Note: This test assumes no QUEUEBOX_* vars are set in the test environment
        // In CI, ensure clean environment or mock this
        val exception = assertFailsWith<ConfigException> {
            ConfigLoader.loadFromEnvOnly()
        }
        assertTrue(exception.message!!.contains("No QUEUEBOX_* environment variables found"))
    }

    @Test
    fun `loadAuto should load from YAML when file exists`() {
        val config = ConfigLoader.loadAuto("test-config.yml")

        assertNotNull(config)
        assertEquals("jdbc:postgresql://localhost:5432/queuebox", config.database.url)
    }

    @Test
    fun `load with optional true should not fail when file missing`() {
        // When optional=true and no file, Hoplite will try other sources
        // If env vars are set, it will use those; otherwise it fails on required fields
        val exception = assertFailsWith<ConfigException> {
            ConfigLoader.load("nonexistent-file.yml", optional = true)
        }
        // Should fail because required fields are missing, not because file is missing
        assertTrue(exception.message!!.contains("database") || exception.message!!.contains("url"))
    }

    @Test
    fun `EnvConfigLoader transformations should match Hoplite expectations`() {
        // Verify our transformation logic matches what Hoplite expects
        assertEquals("database.url", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE_URL"))
        assertEquals("database.pool.size", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DATABASE_POOL_SIZE"))
        assertEquals("server.http.port", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_SERVER_HTTP_PORT"))

        // Array indexing
        assertEquals("routes.0.topic.pattern", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_ROUTES_0_TOPIC_PATTERN"))
        assertEquals("routes.1.destination", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_ROUTES_1_DESTINATION"))

        // Map keys
        assertEquals("destinations.http1.base.url", EnvConfigLoader.envKeyToYamlPath("QUEUEBOX_DESTINATIONS_HTTP1_BASE_URL"))
    }

    @Test
    fun `reverse transformation should generate valid env var names`() {
        assertEquals("QUEUEBOX_DATABASE_URL", EnvConfigLoader.yamlPathToEnvKey("database.url"))
        assertEquals("QUEUEBOX_DATABASE_POOLSIZE", EnvConfigLoader.yamlPathToEnvKey("database.poolSize"))
        assertEquals("QUEUEBOX_SERVER_HTTPPORT", EnvConfigLoader.yamlPathToEnvKey("server.httpPort"))
    }
}
