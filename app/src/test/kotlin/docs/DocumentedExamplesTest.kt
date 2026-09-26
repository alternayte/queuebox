package docs

import org.nxtspec.ConfigLoader
import org.nxtspec.EnvConfigLoader
import org.nxtspec.QueueBoxConfig
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Section 10 of `hardening-doc.md` makes this test mandatory. Item 8 of the whole-effort
 * definition of done makes every documented YAML example loadable.
 *
 * Phase 6 moved four large documents out of `README.md`. The move was mechanical, so the claims
 * that the code contradicts moved with them. This test reads the code, not a second copy of the
 * documentation. It fails when a document and the code disagree.
 *
 * The test covers four claims.
 *
 * 1. Every relative Markdown link resolves against the directory of the file that holds it, and
 *    every site-absolute link of a docs site page resolves to a page of the site.
 * 2. Every `QUEUEBOX_*` name binds a real configuration property.
 * 3. Every documented `state` column width equals the width of the migration.
 * 4. Every fenced YAML block that looks like a QueueBox configuration loads and validates.
 */
class DocumentedExamplesTest {

    private val repositoryRoot = File("..").canonicalFile

    /**
     * `docs/build/` holds the dated plan of each phase. A plan records the state of the code on
     * the day of the plan, so it is a historical record and not the manual. The checks that read
     * the code therefore skip it. The link check still covers it.
     *
     * `docs/specs/` holds the design of each feature, written before the code. A spec names wrong
     * input on purpose, such as the misspelled variable that the strict configuration refuses, so
     * the checks that read the code skip it as well.
     */
    private fun isHistoricalRecord(file: File) = listOf("build", "specs").any { directory ->
        file.canonicalPath.contains("${File.separator}docs${File.separator}$directory${File.separator}")
    }

    /** The pages of the docs site. A site-absolute link like `/concepts/ordering/` names one. */
    private val siteContent = File(repositoryRoot, "site/src/content/docs")

    private val allDocuments: List<File> = buildList {
        add(File(repositoryRoot, "README.md"))
        File(repositoryRoot, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { add(it) }
        siteContent.walkTopDown().filter { it.isFile && it.extension in setOf("md", "mdx") }.forEach { add(it) }
    }.sortedBy { it.path }

    /** The files that the site build writes. A page may link to them, but no source file holds them. */
    private val generatedSiteFiles = setOf("/llms.txt", "/llms-full.txt", "/llms-small.txt")

    /**
     * Resolves a site-absolute link. `/concepts/ordering/` and `/concepts/ordering.md` both name
     * `concepts/ordering.mdx`, and `/` names the landing page.
     */
    private fun sitePageExists(target: String): Boolean {
        if (target in generatedSiteFiles) return true
        val slug = target.trim('/').removeSuffix(".md").ifEmpty { "index" }
        return listOf("$slug.mdx", "$slug.md", "$slug/index.mdx", "$slug/index.md").any { File(siteContent, it).isFile }
    }

    private val manualDocuments: List<File> = allDocuments.filterNot { isHistoricalRecord(it) }

    private fun relativeName(file: File) = file.relativeTo(repositoryRoot).path

    // ------------------------------------------------------------------ links

    @Test
    fun `every relative link resolves against the directory of the file that holds it`() {
        val failures = mutableListOf<String>()
        for (document in allDocuments) {
            document.readLines().forEachIndexed { index, line ->
                Regex("\\[[^\\]]*]\\(([^)\\s]+)\\)").findAll(line).forEach { match ->
                    val target = match.groupValues[1].substringBefore('#')
                    val external = target.startsWith("http://") ||
                        target.startsWith("https://") ||
                        target.startsWith("mailto:")
                    if (target.isEmpty() || external) return@forEach
                    if (target.startsWith("/")) {
                        if (!sitePageExists(target)) {
                            failures += "${relativeName(document)}:${index + 1} links to '$target', " +
                                "which names no page of the docs site."
                        }
                        return@forEach
                    }
                    // The link is relative to the directory of the file that holds it.
                    if (!File(document.parentFile, target).exists()) {
                        failures += "${relativeName(document)}:${index + 1} links to '$target', " +
                            "which does not exist relative to that file."
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "broken relative links:\n" + failures.joinToString("\n"))
    }

    // ----------------------------------------------------- environment variables

    /**
     * The code reads these names directly with `System.getenv`. They are not configuration
     * properties, so `EnvConfigLoader.envKeyToYamlPath` never sees them.
     */
    /**
     * `QUEUEBOX_CONFIG_FILE` names the file that holds the configuration, so it is read before
     * the loader runs and binds no property of its own.
     *
     * The test image variables used to sit here as well. That allowlist was local to this test,
     * and `EnvConfigLoader` never shared it: it treats every `QUEUEBOX_*` name as configuration.
     * The database matrix exported one of them for each job, which made
     * `ConfigLoaderEnvIntegrationTest` see configuration in an environment it requires to be
     * clean, and every matrix job failed. The harness variables are now called
     * `TESTCONTAINERS_*`, outside the configuration namespace, and nothing may be added here
     * that the loader would still read.
     */
    private val nonConfigurationVariables = setOf(
        "QUEUEBOX_CONFIG_FILE"
    )

    @Test
    fun `every documented environment variable binds a real configuration property`() {
        val patterns = leafPaths(QueueBoxConfig::class.java, "", 0)
        assertTrue(patterns.isNotEmpty(), "the reflection walk found no configuration property")

        val failures = mutableListOf<String>()
        for (document in manualDocuments) {
            document.readLines().forEachIndexed { index, line ->
                Regex("QUEUEBOX_[A-Z0-9_]+").findAll(line).forEach { match ->
                    val name = match.value
                    if (name in nonConfigurationVariables) return@forEach
                    val path = envKeyToYamlPath(name)
                    // The deploy page names the Kubernetes Service link forms that the loader
                    // ignores when they bind nothing.
                    if (patterns.none { matches(it, path) } && !EnvConfigLoader.isServiceLink(name)) {
                        failures += "${relativeName(document)}:${index + 1} names $name, which binds " +
                            "'$path'. No such configuration property exists."
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "documented environment variables that bind nothing:\n" + failures.joinToString("\n")
        )
    }

    /**
     * The same rule for the workflow files. A name that the loader reads as configuration must
     * bind a property, wherever it is written. Continuous integration is where the collision
     * above appeared, and no check covered it.
     */
    @Test
    fun `every workflow environment variable binds a real configuration property`() {
        val patterns = leafPaths(QueueBoxConfig::class.java, "", 0)
        val workflows = File(repositoryRoot, ".github/workflows")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
            .sortedBy { it.path }

        val failures = mutableListOf<String>()
        for (workflow in workflows) {
            workflow.readLines().forEachIndexed { index, line ->
                Regex("QUEUEBOX_[A-Z0-9_]+").findAll(line).forEach { match ->
                    val name = match.value
                    if (name in nonConfigurationVariables) return@forEach
                    val path = envKeyToYamlPath(name)
                    if (patterns.none { matches(it, path) }) {
                        failures += "${relativeName(workflow)}:${index + 1} names $name, which binds " +
                            "'$path'. No such configuration property exists."
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "workflow environment variables that bind nothing:\n" + failures.joinToString("\n")
        )
    }

    /**
     * The rule of `EnvConfigLoader.envKeyToYamlPath`. Every single underscore becomes a path
     * separator. A double underscore becomes a literal underscore. There is no camelCase
     * reassembly, so a multi-word leaf must carry no underscore.
     */
    private fun envKeyToYamlPath(name: String): String = name
        .removePrefix("QUEUEBOX_")
        .replace("__", " ")
        .lowercase()
        .replace("_", ".")
        .replace(" ", "_")

    /** Compares one documented path with one pattern. A `*` segment accepts a map key or an index. */
    private fun matches(pattern: String, path: String): Boolean {
        val patternSegments = pattern.split('.')
        val pathSegments = path.split('.')
        if (patternSegments.size != pathSegments.size) return false
        return patternSegments.indices.all { patternSegments[it] == "*" || patternSegments[it] == pathSegments[it] }
    }

    /**
     * Walks the configuration data classes and returns every leaf path in lower case.
     *
     * A map key and a list index become the segment `*`. A sealed class contributes the leaves of
     * every subclass, and the discriminator `type`. The walk uses Java reflection, so the test
     * needs no extra dependency.
     */
    private fun leafPaths(type: Class<*>, prefix: String, depth: Int): Set<String> {
        if (depth > 8) return setOf(prefix)
        val subclasses = type.declaredClasses.filter { type.isAssignableFrom(it) && it != type }
        if (subclasses.isNotEmpty()) {
            val leaves = mutableSetOf(join(prefix, "type"))
            subclasses.forEach { leaves += leafPaths(it, prefix, depth + 1) }
            return leaves
        }
        val fields = type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        if (fields.isEmpty()) return setOf(prefix)
        val leaves = mutableSetOf<String>()
        for (field in fields) {
            val path = join(prefix, field.name.lowercase())
            leaves += when {
                Map::class.java.isAssignableFrom(field.type) ->
                    expand(typeArgument(field, 1), join(path, "*"), depth)
                List::class.java.isAssignableFrom(field.type) ->
                    expand(typeArgument(field, 0), join(path, "*"), depth)
                else -> expand(field.type, path, depth)
            }
        }
        return leaves
    }

    private fun expand(type: Class<*>?, path: String, depth: Int): Set<String> =
        if (type == null || isScalar(type)) setOf(path) else leafPaths(type, path, depth + 1)

    private fun typeArgument(field: Field, index: Int): Class<*>? {
        val generic = field.genericType as? ParameterizedType ?: return null
        return generic.actualTypeArguments.getOrNull(index) as? Class<*>
    }

    private fun isScalar(type: Class<*>): Boolean = type.isPrimitive ||
        type.isEnum ||
        type.name.startsWith("java.") ||
        type.name.startsWith("kotlin.") ||
        type.simpleName == "Secret"

    private fun join(prefix: String, segment: String) = if (prefix.isEmpty()) segment else "$prefix.$segment"

    // ------------------------------------------------------------ column widths

    @Test
    fun `every documented state column width equals the migration`() {
        val migrations = File(repositoryRoot, "postgres/src/main/resources/db/postgresql")
        val widths = migrations.listFiles().orEmpty()
            .filter { it.extension == "sql" }
            .flatMap { file ->
                Regex("state\\s+VARCHAR\\((\\d+)\\)").findAll(file.readText()).map { it.groupValues[1] }.toList()
            }
            .toSet()
        assertTrue(widths.size == 1, "the migrations declare more than one state width: $widths")
        val expected = widths.first()

        val forms = listOf(
            Regex("^\\s*state\\s+N?VARCHAR\\((\\d+)\\)"),
            Regex("\\|\\s*`state`\\s*\\|\\s*`N?VARCHAR\\((\\d+)\\)`")
        )

        val failures = mutableListOf<String>()
        for (document in manualDocuments) {
            document.readLines().forEachIndexed { index, line ->
                for (form in forms) {
                    form.findAll(line).forEach { match ->
                        if (match.groupValues[1] != expected) {
                            failures += "${relativeName(document)}:${index + 1} states a state column of " +
                                "width ${match.groupValues[1]}. The migration declares $expected."
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "wrong state column widths:\n" + failures.joinToString("\n"))
    }

    // ------------------------------------------------------------ YAML examples

    private val topLevelKeys = QueueBoxConfig::class.java.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()

    /** A block must hold at least one of these keys before the test treats it as a configuration. */
    private val markerKeys = setOf("destinations", "routes", "sources", "database", "server", "outbox", "inbox")

    /** The smallest database section that validates. A fragment merges onto it. */
    private val baseDatabase = linkedMapOf(
        "type" to "postgresql",
        "url" to "jdbc:postgresql://localhost:5432/queuebox",
        "username" to "queuebox",
        "password" to "documented-example-password"
    )

    /**
     * The configuration is strict, so a key that an example misspells stops its start. Every
     * example stack under `examples/` and the packaged fallback therefore load here.
     */
    @Test
    fun `every example configuration and the packaged configuration load`() {
        val examples = File(repositoryRoot, "examples").walkTopDown()
            .filter { it.isFile && it.name == "queuebox.yml" }
            .sortedBy { it.path }
            .toList()
        assertTrue(examples.isNotEmpty(), "no example configuration found")

        // Some example stacks set the database in their compose file, not in the configuration.
        val database = mapOf(
            "QUEUEBOX_DATABASE_URL" to "jdbc:postgresql://postgres:5432/queuebox",
            "QUEUEBOX_DATABASE_USERNAME" to "queuebox",
            "QUEUEBOX_DATABASE_PASSWORD" to "queuebox"
        )
        val failures = mutableListOf<String>()
        for (example in examples) {
            try {
                ConfigLoader.load(env = { database + ("QUEUEBOX_CONFIG_FILE" to example.absolutePath) })
            } catch (e: Exception) {
                failures += "${relativeName(example)} does not load: ${e.message}"
            }
        }
        try {
            ConfigLoader.load(env = { emptyMap() })
        } catch (e: Exception) {
            failures += "the packaged queuebox.yml does not load: ${e.message}"
        }
        assertTrue(failures.isEmpty(), "configurations that fail:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `every documented QueueBox YAML example loads and validates`() {
        val failures = mutableListOf<String>()
        for (document in manualDocuments) {
            fencedYamlBlocks(document.readText()).forEachIndexed { index, block ->
                val keys = topLevelKeysOf(block)
                if (keys.isEmpty() || !keys.all { it in topLevelKeys } || keys.none { it in markerKeys }) {
                    return@forEachIndexed
                }
                // A `file:` secret names a path of the deployment host. The test machine holds no
                // such file, so the block states a real shape that this test cannot load.
                if (block.contains("file:/")) return@forEachIndexed
                // A page can show a part of a configuration that cannot load alone, such as a
                // route that names a destination of another block. It says so on its first line.
                if (block.lineSequence().firstOrNull()?.trim() == "# fragment") return@forEachIndexed

                val merged = mergeOntoBase(block)
                // A document writes a value that the deployment supplies as a placeholder. Give
                // each placeholder a value, so the example tests the shape and not the secret.
                Regex("\\$\\{([A-Za-z0-9_]+)}").findAll(merged).forEach { match ->
                    System.setProperty(match.groupValues[1], "documented-example-value")
                }
                val file = File.createTempFile("documented-example", ".yml")
                file.deleteOnExit()
                file.writeText(merged)
                try {
                    ConfigLoader.load(optional = true, env = { mapOf("QUEUEBOX_CONFIG_FILE" to file.absolutePath) })
                } catch (e: Exception) {
                    failures += "${relativeName(document)} block $index does not load: ${e.message}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "documented YAML examples that fail:\n" + failures.joinToString("\n"))
    }

    /**
     * Adds the base database keys that the block omits, so a fragment can reach the validator.
     *
     * The block keeps every key that it declares. The merge only fills a gap.
     */
    private fun mergeOntoBase(block: String): String {
        val lines = block.lines().toMutableList()
        val start = lines.indexOfFirst { Regex("^database:").containsMatchIn(it) }
        if (start < 0) {
            val section = buildString {
                appendLine("database:")
                baseDatabase.forEach { (key, value) -> appendLine("  $key: $value") }
            }
            return section + block
        }
        var end = start + 1
        while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith(" "))) end++
        val body = lines.subList(start + 1, end)
        val present = body.mapNotNull { Regex("^\\s+([A-Za-z][A-Za-z0-9_-]*):").find(it)?.groupValues?.get(1) }.toSet()
        val missing = baseDatabase.filterKeys { it !in present }.map { (key, value) -> "  $key: $value" }
        lines.addAll(start + 1, missing)
        return lines.joinToString("\n")
    }

    /** Returns the body of every fence that declares the language `yaml` or `yml`. */
    private fun fencedYamlBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var inside = false
        val current = StringBuilder()
        for (line in text.lines()) {
            val fence = line.trimStart().startsWith("```")
            when {
                !inside && fence -> {
                    val language = line.trimStart().removePrefix("```").trim().lowercase()
                    if (language == "yaml" || language == "yml") {
                        inside = true
                        current.setLength(0)
                    }
                }
                inside && fence -> {
                    inside = false
                    blocks += current.toString()
                }
                inside -> current.appendLine(line)
            }
        }
        return blocks
    }

    private fun topLevelKeysOf(block: String): Set<String> = block.lines()
        .mapNotNull { Regex("^([A-Za-z][A-Za-z0-9_-]*):").find(it)?.groupValues?.get(1) }
        .toSet()
}
