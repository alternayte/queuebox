package org.nxtspec

import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.transformer.PathNormalizer
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

/**
 * Refuses a configuration that holds a key QueueBox does not know. Fixes issue #65.
 *
 * The check walks the merged tree of every source against [QueueBoxConfig], before Hoplite
 * decodes it. Hoplite ignores a key that binds nothing, so a misspelled key used to leave its
 * setting at the default in silence, and a key of the wrong kind turned a destination into
 * another kind. The check reports:
 * - a key that binds no setting, with "did you mean" for a near miss,
 * - a destination or auth block without `type`, and a `type` that names no kind,
 * - a key that the kind selected by `type` does not take.
 *
 * A node that a `QUEUEBOX_*` variable set is reported by the name of the variable. One
 * [ConfigException] lists every problem, so an operator fixes the whole configuration in one pass.
 *
 * The tree is the one that Hoplite decodes, so it holds normalised keys: lower case, without `-`
 * or `_`. A parameter matches a key when its normalised name equals the key, which is how
 * Hoplite's `PathNormalizer` and `LowercaseParamMapper` bind it. A message prints the key as the
 * source wrote it.
 */
internal class StrictConfigCheck(envKeys: Collection<String>) {

    /** Each variable that reached the tree, with its normalised path in the tree. */
    private val envPaths: Map<String, List<String>> = EnvConfigLoader.treePaths(envKeys)
        .mapValues { (_, path) -> path.map(PathNormalizer::transformPathElement) }

    /** A position in the tree: the normalised segments, and the path as the source wrote it. */
    private data class At(val segments: List<String>, val display: String) {
        fun key(key: String, written: String) =
            At(segments + key, if (display.isEmpty()) written else "$display.$written")
        fun index(index: Int) = At(segments + index.toString(), "$display[$index]")
    }

    private val problems = mutableListOf<String>()

    /** Throws a [ConfigException] that lists every problem of [root], or returns if there is none. */
    fun check(root: Node) {
        walk(root, QueueBoxConfig::class, At(emptyList(), ""))
        if (problems.isEmpty()) return
        val count = if (problems.size == 1) "1 error" else "${problems.size} errors"
        throw ConfigException(
            "The configuration has $count. Fix each one and start QueueBox again.\n" +
                problems.joinToString("\n") { "  - $it" }
        )
    }

    private fun walk(node: Node, type: KType, at: At) {
        val kclass = type.classifier as? KClass<*> ?: return
        when {
            kclass.isSubclassOf(Map::class) -> type.arguments.getOrNull(1)?.type?.let { walkMap(node, it, at) }
            kclass.isSubclassOf(Collection::class) -> type.arguments.firstOrNull()?.type?.let { walkList(node, it, at) }
            else -> walk(node, kclass, at)
        }
    }

    /** A map of user names, such as `destinations`. Its keys are free, and each value is checked. */
    private fun walkMap(node: Node, value: KType, at: At) {
        if (node !is MapNode) return
        entries(node).forEach { (key, written, child) -> walk(child, value, at.key(key, written)) }
    }

    private fun walkList(node: Node, element: KType, at: At) {
        if (node !is ArrayNode) return
        node.elements.forEachIndexed { index, child -> walk(child, element, at.index(index)) }
    }

    private fun walk(node: Node, kclass: KClass<*>, at: At) {
        // A scalar in place of a section, or a null, is a decode failure that Hoplite reports.
        if (node !is MapNode) return
        when {
            kclass.isSealed -> walkSealed(node, kclass, at)
            kclass.isData -> walkFields(node, kclass, at, kind = null, sealed = null)
        }
    }

    private fun walkSealed(node: MapNode, sealed: KClass<*>, at: At) {
        val kinds = ConfigKinds.of(sealed)
        val choices = kinds.keys.joinToString(", ")
        val kind = when (val typeNode = node.atKey(ConfigKinds.TYPE_KEY)) {
            is Undefined -> ConfigKinds.default(sealed)?.let { default -> kinds.entries.first { it.value == default } }
                ?: return report(at, node, "missing type; set one of $choices")
            is StringNode -> kinds.entries.firstOrNull { it.key == typeNode.value }
                ?: return report(
                    at.key(ConfigKinds.TYPE_KEY, ConfigKinds.TYPE_KEY),
                    typeNode,
                    "unknown type ${typeNode.value}" +
                        (nearest(typeNode.value, kinds.keys)?.let { "; did you mean $it?" } ?: "; set one of $choices")
                )
            else -> return report(
                at.key(ConfigKinds.TYPE_KEY, ConfigKinds.TYPE_KEY),
                typeNode,
                "type must be one of $choices"
            )
        }
        walkFields(node, kind.value, at, kind = kind.key, sealed = sealed)
    }

    /**
     * Checks every key of [node] against the constructor of [kclass]. For a kind of a sealed class,
     * [kind] names its `type` value, and `type` itself is a known key.
     */
    private fun walkFields(node: MapNode, kclass: KClass<*>, at: At, kind: String?, sealed: KClass<*>?) {
        val params = parameters(kclass)
        for ((key, written, child) in entries(node)) {
            if (kind != null && key == ConfigKinds.TYPE_KEY) continue
            val param = params[key]
            if (param != null) {
                walk(child, param.type, at.key(key, written))
                continue
            }
            val names = params.values.mapNotNull { it.name }
            val suggestion = nearest(written, names)?.let { "; did you mean $it?" }
            if (kind == null || sealed == null) {
                report(at.key(key, written), child, "unknown key" + (suggestion ?: ""), unknownKey = true)
            } else {
                val takers = ConfigKinds.of(sealed).filter { (_, other) -> key in parameters(other) }.keys
                val hint =
                    suggestion
                        ?: takers.takeIf { it.isNotEmpty() }?.let { "; type ${it.joinToString(" and ")} takes it" }
                report(at.key(key, written), child, "unknown key for type $kind" + (hint ?: ""), unknownKey = true)
            }
        }
    }

    /**
     * Records one problem. An unknown key that the environment set is named by its variables,
     * and one that a file set by its path. A node can come from both, for example a section where
     * the file writes one key and a variable adds another. Any other problem names the path, and
     * the variables that set it.
     */
    private fun report(at: At, node: Node, problem: String, unknownKey: Boolean = false) {
        val variables = envPaths.filterValues { path ->
            path.size >= at.segments.size && path.subList(0, at.segments.size) == at.segments
        }.keys.sorted()
        if (!unknownKey) {
            val setBy = if (variables.isEmpty()) "" else " (set by ${variables.joinToString(", ")})"
            problems += "${at.display}: $problem$setBy"
            return
        }
        val fromFile = leaves(node, at.segments).any { leaf -> variables.none { envPaths[it] == leaf } }
        if (fromFile || variables.isEmpty()) problems += "${at.display}: $problem"
        variables.forEach { name -> problems += "$name: ${envProblem(name, at, problem)}" }
    }

    /** Rewrites an unknown key for a variable: the key becomes a variable, and a suggestion a variable name. */
    private fun envProblem(name: String, at: At, problem: String): String {
        val base = problem.replace("unknown key", "unknown variable").substringBefore("; did you mean")
        val suggested = Regex("did you mean (\\S+)\\?").find(problem)?.groupValues?.get(1) ?: return base
        val path = EnvConfigLoader.envKeyToYamlPath(name).split(".").toMutableList()
        path[at.segments.size - 1] = suggested
        return "$base; did you mean ${EnvConfigLoader.yamlPathToEnvKey(path.joinToString("."))}?"
    }

    /** The normalised path of every leaf under [node]. A section with no leaf counts as a leaf. */
    private fun leaves(node: Node, segments: List<String>): List<List<String>> = when (node) {
        is MapNode -> entries(node).flatMap { (key, _, child) ->
            leaves(child, segments + key)
        }.ifEmpty { listOf(segments) }
        is ArrayNode -> node.elements.flatMapIndexed { index, child -> leaves(child, segments + index.toString()) }
            .ifEmpty { listOf(segments) }
        else -> listOf(segments)
    }

    private data class Entry(val key: String, val written: String, val node: Node)

    /**
     * The entries of [node], each with the key as its source wrote it. Hoplite keeps the source
     * path of a node in `sourceKey`, and a variable's node keeps the whole variable path there, so
     * a written key that does not normalise to the tree key falls back to the tree key.
     */
    private fun entries(node: MapNode): List<Entry> = node.map.map { (key, child) ->
        val parent = node.sourceKey
        val source = child.sourceKey
        val written = when {
            source == null -> key
            parent == null -> source
            else -> source.removePrefix("$parent.")
        }
        Entry(key, if (PathNormalizer.transformPathElement(written) == key) written else key, child)
    }

    private fun parameters(kclass: KClass<*>): Map<String, KParameter> =
        (kclass.primaryConstructor ?: kclass.constructors.first()).parameters
            .filter { it.name != null }
            .associateBy { PathNormalizer.transformPathElement(it.name!!) }

    companion object {
        /**
         * Checks if a `QUEUEBOX_*` variable could bind a setting: its path names a setting of
         * [QueueBoxConfig], a map key or a list index. A sealed class accepts a key of any kind,
         * because the kind of a node is only known once every source is merged.
         */
        fun bindsSetting(envKey: String): Boolean {
            val segments = EnvConfigLoader.envKeyToYamlPath(envKey).split(".").map(PathNormalizer::transformPathElement)
            return binds(QueueBoxConfig::class, segments)
        }

        private fun binds(type: KType, segments: List<String>): Boolean {
            val kclass = type.classifier as? KClass<*> ?: return false
            return when {
                kclass.isSubclassOf(Map::class) -> segments.isNotEmpty() &&
                    (type.arguments.getOrNull(1)?.type?.let { binds(it, segments.drop(1)) } ?: false)
                kclass.isSubclassOf(Collection::class) -> segments.isNotEmpty() &&
                    segments[0].all(Char::isDigit) &&
                    (type.arguments.firstOrNull()?.type?.let { binds(it, segments.drop(1)) } ?: false)
                else -> binds(kclass, segments)
            }
        }

        private fun binds(kclass: KClass<*>, segments: List<String>): Boolean = when {
            kclass.isSealed -> segments == listOf(ConfigKinds.TYPE_KEY) ||
                kclass.sealedSubclasses.any { binds(it, segments) }
            kclass.isData -> segments.isNotEmpty() &&
                (kclass.primaryConstructor ?: kclass.constructors.first()).parameters
                    .firstOrNull { it.name?.let(PathNormalizer::transformPathElement) == segments[0] }
                    ?.let { binds(it.type, segments.drop(1)) } ?: false
            else -> segments.isEmpty()
        }

        /**
         * The candidate closest to [written], or null if none is near. A near miss is at most two
         * edits away, one for a short key, where a swap of two neighbours counts as one edit.
         * Case, `-` and `_` do not count, because Hoplite ignores them.
         */
        fun nearest(written: String, candidates: Collection<String>): String? {
            val target = PathNormalizer.transformPathElement(written)
            val limit = if (target.length <= SHORT_KEY) 1 else 2
            return candidates
                .map { it to distance(target, PathNormalizer.transformPathElement(it)) }
                .filter { (_, d) -> d <= limit }
                .minByOrNull { (_, d) -> d }
                ?.first
        }

        private const val SHORT_KEY = 4

        /** The optimal string alignment distance: insert, delete, substitute, or swap two neighbours. */
        private fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { i ->
                IntArray(b.length + 1) { j ->
                    if (i == 0) {
                        j
                    } else if (j == 0) {
                        i
                    } else {
                        0
                    }
                }
            }
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (isSwap(a, b, i, j)) {
                        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                    }
                }
            }
            return d[a.length][b.length]
        }

        /** Checks if the last two characters of `a[0, i)` are those of `b[0, j)`, swapped. */
        private fun isSwap(a: String, b: String, i: Int, j: Int): Boolean =
            i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
    }
}
