package org.nxtspec

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.sources.MapPropertySource
import java.io.File

/**
 * Loads QueueBox configuration from YAML files and/or environment variables.
 *
 * Configuration sources (in order of precedence, highest first):
 * 1. Environment variables with QUEUEBOX_ prefix
 * 2. The external file named by QUEUEBOX_CONFIG_FILE, or /etc/queuebox/queuebox.yml when the
 *    environment names no other path
 * 3. The packaged classpath YAML resource, but ONLY when no external file exists. An external
 *    file replaces the resource. It does not overlay it, so it must be complete.
 *
 * Usage modes:
 * - YAML only: Use [load] with a path to your configuration file
 * - YAML + env overrides: Use [load] - env vars will override YAML values
 * - Env only: Use [loadFromEnvOnly] for container deployments without YAML
 *
 * Environment variable naming convention:
 * - QUEUEBOX_DATABASE_URL → database.url
 * - QUEUEBOX_SERVER_HTTPPORT → server.httpPort. A leaf name carries no underscore, because every
 *   single underscore becomes a level separator.
 * - QUEUEBOX_ROUTES_0_TOPICPATTERN → routes[0].topicPattern
 *
 * @see EnvConfigLoader for environment variable transformation utilities
 */
object ConfigLoader {

    /** The environment variable that names an external configuration file. */
    const val CONFIG_FILE_ENV = "QUEUEBOX_CONFIG_FILE"

    /** The external configuration file that QueueBox reads if the environment names no other. */
    const val DEFAULT_EXTERNAL_PATH = "/etc/queuebox/queuebox.yml"

    /** The Hoplite message that says every source was absent or empty. */
    private const val EMPTY_CONFIG_MARKER = "The applied config was empty"

    /** Names every source that QueueBox read, so the user knows where to put the configuration. */
    private fun noSourceException(externalPath: String, resourcePath: String, cause: ConfigException) = ConfigException(
        "No configuration was found. QueueBox read three sources and every one was empty. " +
            "1. The QUEUEBOX_* environment variables, for example QUEUEBOX_DATABASE_URL, " +
            "QUEUEBOX_DATABASE_USERNAME and QUEUEBOX_DATABASE_PASSWORD. " +
            "2. The file $externalPath, which $CONFIG_FILE_ENV can rename. " +
            "3. The packaged resource /$resourcePath. " +
            "Set the environment variables, or write the file.",
        cause
    )

    /**
     * The `QUEUEBOX_` variables, mapped to the configuration paths they set.
     *
     * The source is a map, not `EnvironmentVariablesPropertySource`. That source binds a path
     * segment on a double underscore, so `QUEUEBOX_DATABASE_URL` set nothing and failed in
     * silence. `EnvConfigLoader.envKeyToYamlPath` holds the convention that this project
     * documents, that finding F-073 of `hardening-doc.md` names, and that every validation error
     * message prints through `EnvConfigLoader.yamlPathToEnvKey`. This source makes that one
     * convention the convention that works.
     */
    private fun createEnvSource(env: () -> Map<String, String>) = MapPropertySource(EnvConfigLoader.loadFromEnv(env))

    /**
     * Loads configuration from YAML file with optional environment variable overrides.
     *
     * Environment variables with QUEUEBOX_ prefix will override values from YAML.
     *
     * @param path Path to the YAML configuration file (relative to resources or absolute)
     * @param optional If true, missing YAML file is not an error (env-only mode)
     * @param env Supplier of the environment. The tests replace it.
     * @return Validated QueueBoxConfig
     * @throws ConfigException if required configuration is missing
     * @throws IllegalArgumentException if configuration validation fails
     */
    fun load(
        path: String = "queuebox.yml",
        optional: Boolean = false,
        env: () -> Map<String, String> = { System.getenv() }
    ): QueueBoxConfig {
        val externalPath = env()[CONFIG_FILE_ENV] ?: DEFAULT_EXTERNAL_PATH
        // F-076: the packaged resource is a fallback, not an overlay. Hoplite cascades a map node
        // key by key, so an external file that declares one destination used to inherit every
        // destination and every source of the packaged file. A deployment then served an inbox
        // endpoint that its own configuration never declared. An external file therefore replaces
        // the resource. An environment variable still wins over both.
        val externalFile = File(externalPath)
        val config = try {
            ConfigLoaderBuilder.default()
                .addDecoder(SecretDecoder())
                .addPropertySource(createEnvSource(env))
                .apply {
                    if (externalFile.isFile) {
                        addFileSource(externalFile)
                    } else {
                        addResourceSource("/$path", optional = optional)
                    }
                }
                .build()
                .loadConfigOrThrow<QueueBoxConfig>()
        } catch (e: ConfigException) {
            // Every source was absent or empty. Hoplite then reports "The applied config was
            // empty", which does not tell the user what to do next. Name the sources instead.
            if (e.message?.contains(EMPTY_CONFIG_MARKER) == true) throw noSourceException(externalPath, path, e)
            throw e
        }
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
                    "Set required variables (e.g., QUEUEBOX_DATABASE_URL, " +
                    "QUEUEBOX_DATABASE_USERNAME, QUEUEBOX_DATABASE_PASSWORD) " +
                    "or provide a queuebox.yml configuration file."
            )
        }

        val config = ConfigLoaderBuilder.default()
            .addDecoder(SecretDecoder())
            .addPropertySource(createEnvSource { System.getenv() })
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
     * @param env Supplier of the environment. The tests replace it.
     * @return Validated QueueBoxConfig
     * @throws ConfigException if required configuration is missing
     */
    fun loadAuto(path: String = "queuebox.yml", env: () -> Map<String, String> = { System.getenv() }): QueueBoxConfig =
        load(path, optional = true, env = env)
}
