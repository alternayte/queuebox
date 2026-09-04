package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource

/**
 * Loads QueueBox configuration from YAML files and/or environment variables.
 *
 * Configuration sources (in order of precedence, highest first):
 * 1. Environment variables with QUEUEBOX_ prefix
 * 2. YAML configuration file
 *
 * Usage modes:
 * - YAML only: Use [load] with a path to your configuration file
 * - YAML + env overrides: Use [load] - env vars will override YAML values
 * - Env only: Use [loadFromEnvOnly] for container deployments without YAML
 *
 * Environment variable naming convention:
 * - QUEUEBOX_DATABASE_URL → database.url
 * - QUEUEBOX_SERVER_HTTP_PORT → server.httpPort
 * - QUEUEBOX_ROUTES_0_TOPIC_PATTERN → routes[0].topicPattern
 *
 * @see EnvConfigLoader for environment variable transformation utilities
 */
object ConfigLoader {

    private fun createEnvSource() = EnvironmentVariablesPropertySource(
        useUnderscoresAsSeparator = true,
        allowUppercaseNames = true,
        prefix = EnvConfigLoader.PREFIX
    )

    /**
     * Loads configuration from YAML file with optional environment variable overrides.
     *
     * Environment variables with QUEUEBOX_ prefix will override values from YAML.
     *
     * @param path Path to the YAML configuration file (relative to resources or absolute)
     * @param optional If true, missing YAML file is not an error (env-only mode)
     * @return Validated QueueBoxConfig
     * @throws ConfigException if required configuration is missing
     * @throws IllegalArgumentException if configuration validation fails
     */
    fun load(path: String = "queuebox.yml", optional: Boolean = false): QueueBoxConfig {
        val config = ConfigLoaderBuilder.default()
            .addDecoder(SecretDecoder())
            .addResourceSource("/$path", optional = optional)
            .addPropertySource(createEnvSource())
            .build()
            .loadConfigOrThrow<QueueBoxConfig>()
        return ConfigValidator.validate(config)
    }

    /**
     * Loads configuration entirely from environment variables.
     *
     * Use this for container deployments where configuration is provided via env vars.
     * All required fields must be set via QUEUEBOX_* environment variables.
     *
     * @return Validated QueueBoxConfig
     * @throws ConfigException if required configuration is missing
     * @throws IllegalArgumentException if configuration validation fails
     */
    fun loadFromEnvOnly(): QueueBoxConfig {
        if (!EnvConfigLoader.hasEnvConfig()) {
            throw ConfigException(
                "No QUEUEBOX_* environment variables found. " +
                    "Set required variables (e.g., QUEUEBOX_DATABASE_URL, QUEUEBOX_DATABASE_USERNAME, QUEUEBOX_DATABASE_PASSWORD) " +
                    "or provide a queuebox.yml configuration file."
            )
        }

        val config = ConfigLoaderBuilder.default()
            .addDecoder(SecretDecoder())
            .addPropertySource(createEnvSource())
            .build()
            .loadConfigOrThrow<QueueBoxConfig>()
        return ConfigValidator.validate(config)
    }

    /**
     * Loads configuration with automatic mode detection.
     *
     * - If YAML file exists: loads from YAML with env overrides
     * - If no YAML file: loads entirely from environment variables
     *
     * @param path Path to check for YAML configuration
     * @return Validated QueueBoxConfig
     * @throws ConfigException if required configuration is missing
     */
    fun loadAuto(path: String = "queuebox.yml"): QueueBoxConfig {
        return load(path, optional = true)
    }
}
