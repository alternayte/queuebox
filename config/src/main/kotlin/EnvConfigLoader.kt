package org.nxtspec

/**
 * Utility for loading and transforming QUEUEBOX_* prefixed environment variables.
 *
 * Naming Convention:
 * - Environment variables use QUEUEBOX_ prefix with uppercase and underscores
 * - YAML paths use lowercase with dots
 * - Single underscore in env var maps to dot in YAML path
 * - Double underscore in env var maps to single underscore in YAML path (for literal underscores)
 *
 * Examples:
 * - QUEUEBOX_DATABASE_URL → database.url
 * - QUEUEBOX_SERVER_HTTP_PORT → server.httpPort (Hoplite handles camelCase)
 * - QUEUEBOX_DATABASE__POOL_SIZE → database_pool.size (literal underscore)
 * - QUEUEBOX_ROUTES_0_TOPIC_PATTERN → routes[0].topicPattern (array indexing)
 */
object EnvConfigLoader {
    const val PREFIX = "QUEUEBOX_"

    /**
     * Loads all QUEUEBOX_* environment variables and transforms them to YAML-compatible paths.
     *
     * @param envProvider Function to get environment variables (defaults to System.getenv())
     * @return Map of YAML paths to values
     */
    fun loadFromEnv(envProvider: () -> Map<String, String> = { System.getenv() }): Map<String, String> = envProvider()
        .filterKeys { it.startsWith(PREFIX) }
        .mapKeys { (key, _) -> envKeyToYamlPath(key) }

    /**
     * Transforms an environment variable key to a YAML-compatible path.
     *
     * Transformation rules:
     * 1. Remove QUEUEBOX_ prefix
     * 2. Handle double underscores as literal underscore escapes
     * 3. Convert single underscores to dots
     * 4. Convert to lowercase
     *
     * @param envKey The environment variable key (e.g., "QUEUEBOX_DATABASE_URL")
     * @return The YAML path (e.g., "database.url")
     */
    fun envKeyToYamlPath(envKey: String): String {
        return envKey
            .removePrefix(PREFIX)
            .replace("__", "\u0000") // Temporarily replace double underscore
            .lowercase()
            .replace("_", ".")
            .replace("\u0000", "_") // Restore literal underscores
    }

    /**
     * Transforms a YAML path to the corresponding environment variable key.
     * Useful for generating documentation or error messages.
     *
     * @param yamlPath The YAML path (e.g., "database.url")
     * @return The environment variable key (e.g., "QUEUEBOX_DATABASE_URL")
     */
    fun yamlPathToEnvKey(yamlPath: String): String = PREFIX + yamlPath
        .replace("_", "__") // Escape literal underscores first
        .replace(".", "_")
        .uppercase()

    /**
     * Gets all QUEUEBOX_* environment variable keys from the current environment.
     *
     * @param envProvider Function to get environment variables (defaults to System.getenv())
     * @return Set of environment variable keys with QUEUEBOX_ prefix
     */
    fun getQueueBoxEnvKeys(envProvider: () -> Map<String, String> = { System.getenv() }): Set<String> = envProvider()
        .keys
        .filter { it.startsWith(PREFIX) }
        .toSet()

    /**
     * Checks if any QUEUEBOX_* environment variables are set.
     *
     * @param envProvider Function to get environment variables (defaults to System.getenv())
     * @return true if at least one QUEUEBOX_* variable exists
     */
    fun hasEnvConfig(envProvider: () -> Map<String, String> = { System.getenv() }): Boolean =
        envProvider().keys.any { it.startsWith(PREFIX) }
}
