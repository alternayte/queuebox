package org.nxtspec

/**
 * Utility for loading and transforming QUEUEBOX_* prefixed environment variables.
 *
 * Naming Convention:
 * - Environment variables use QUEUEBOX_ prefix with uppercase and underscores
 * - YAML paths use lowercase with dots
 * - Single underscore in env var maps to dot in YAML path. There is no camelCase reassembly, so
 *   QUEUEBOX_SERVER_HTTP_PORT becomes server.http.port and binds nothing.
 * - Double underscore in env var maps to single underscore in YAML path (for literal underscores)
 *
 * Examples:
 * - QUEUEBOX_DATABASE_URL → database.url
 * - QUEUEBOX_SERVER_HTTPPORT → server.httpPort (a leaf name carries no underscore)
 * - QUEUEBOX_DATABASE__POOL_SIZE → database_pool.size (literal underscore)
 * - QUEUEBOX_ROUTES_0_TOPICPATTERN → routes[0].topicPattern (a list index)
 *
 * A path segment that holds only digits is a list index. `routes` is the only list in the
 * configuration. Hoplite builds a list from a real nested value, not from a flattened key, so
 * [nestEnv] turns the flat paths into nested maps and lists before Hoplite reads them.
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
     * Loads the QUEUEBOX_ variables as the nested structure that Hoplite binds.
     *
     * [loadFromEnv] returns one flat path per variable, for example `routes.0.topicpattern`.
     * Hoplite reads such a path as a map that is keyed by `0`, and it refuses to build a list
     * from a map. A deployment that configured QueueBox with the variables alone therefore could
     * not declare a route. This function builds the nested maps, and turns a node whose keys are
     * all digits into a list in index order, so a list binds.
     *
     * @param envProvider Function to get environment variables (defaults to System.getenv())
     * @return The nested structure, ready for a Hoplite map property source
     */
    fun loadNestedFromEnv(envProvider: () -> Map<String, String> = { System.getenv() }): Map<String, Any> =
        nestEnv(loadFromEnv(envProvider))

    /**
     * Builds the structure that a Hoplite map property source binds.
     *
     * The two levels do not take the same shape, and each silently binds the wrong thing when it
     * receives the other. A top level key must stay a flat dotted path, because a nested map
     * there binds the container name in place of its content. Inside a list element the opposite
     * holds, because a dotted key there binds nothing at all. So a path that holds no list index
     * stays flat, and a path that holds one becomes a list of fully nested elements.
     *
     * A sparse index does not fail. The indices order the list and the list holds no gap, because
     * a gap carries no meaning for a list that Hoplite binds by position.
     */
    internal fun nestEnv(flat: Map<String, String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lists = mutableMapOf<String, MutableMap<Int, MutableMap<String, String>>>()

        for ((path, value) in flat) {
            val segments = path.split(".")
            val indexAt = segments.indexOfFirst { it.isNotEmpty() && it.all(Char::isDigit) }
            if (indexAt < 0) {
                result[path] = value
                continue
            }
            val root = segments.subList(0, indexAt).joinToString(".")
            val index = segments[indexAt].toInt()
            val rest = segments.subList(indexAt + 1, segments.size).joinToString(".")
            lists.getOrPut(root) { mutableMapOf() }.getOrPut(index) { mutableMapOf() }[rest] = value
        }

        for ((root, byIndex) in lists) {
            result[root] = byIndex.toSortedMap().values.map { element ->
                // A list of scalars carries one empty remainder per index, for example
                // QUEUEBOX_TOPICS_0. Such an element is the value itself, not a map.
                element[""] ?: nestFully(element)
            }
        }
        return result
    }

    /** Builds fully nested maps and lists. A list element takes this shape, a top level key does not. */
    private fun nestFully(flat: Map<String, String>): Map<String, Any> {
        val root = mutableMapOf<String, Any>()
        for ((path, value) in flat) {
            val segments = path.split(".")
            descend(root, segments.dropLast(1))[segments.last()] = value
        }
        return collapseIndexNodes(root)
    }

    /** Walks the path, and creates a map for a segment that holds none yet. */
    private fun descend(root: MutableMap<String, Any>, path: List<String>): MutableMap<String, Any> {
        var node = root
        for (segment in path) {
            val child = node[segment]

            @Suppress("UNCHECKED_CAST")
            node = child as? MutableMap<String, Any>
                ?: mutableMapOf<String, Any>().also { node[segment] = it }
        }
        return node
    }

    /** Replaces every node whose keys are all digits with a list, in index order. */
    private fun collapseIndexNodes(node: Map<String, Any>): Map<String, Any> =
        node.mapValues { (_, value) -> collapseValue(value) }

    private fun collapseValue(value: Any): Any {
        if (value !is Map<*, *>) return value

        @Suppress("UNCHECKED_CAST")
        val collapsed = collapseIndexNodes(value as Map<String, Any>)
        val isIndexNode = collapsed.isNotEmpty() &&
            collapsed.keys.all { key -> key.isNotEmpty() && key.all(Char::isDigit) }
        return if (isIndexNode) {
            collapsed.entries.sortedBy { it.key.toInt() }.map { it.value }
        } else {
            collapsed
        }
    }

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
