package org.nxtspec

import kotlinx.serialization.json.JsonElement

/**
 * Result of routing a message to a destination.
 *
 * @property destination The resolved destination to publish to
 * @property routingKey The routing key that the route resolved, or null when the route sets no
 *   routingKeyTemplate. A null value lets the destination apply its own fallback. See F-004.
 * @property routeTransform Optional transform configured at the route level
 * @property destinationTransform Optional transform configured at the destination level
 */
data class RoutingResult(
    val destination: Destination,
    val routingKey: String?,
    val routeTransform: TransformConfig? = null,
    val destinationTransform: TransformConfig? = null
)

/**
 * Routes messages to destinations based on topic pattern matching.
 *
 * @property compiledRoutes Route configurations with their topic patterns compiled once
 * @property destinations Map of destination name to Destination instances
 * @property destinationTransforms Map of destination name to optional transform config
 * @property routingKeyRenderer Renderer for routing key templates with payload field substitution
 */
class MessageRouter(
    routes: List<RouteConfig>,
    private val destinations: Map<String, Destination>,
    private val destinationTransforms: Map<String, TransformConfig?> = emptyMap(),
    private val routingKeyRenderer: RoutingKeyRenderer = RoutingKeyRenderer(),
    patternCompiler: (String) -> Regex = ::compileTopicPattern
) {
    /**
     * Every route with its topic pattern compiled once. See F-026. A pattern compiled per message
     * wastes time on the poller thread and lets a pathological pattern block that thread.
     */
    private val compiledRoutes: List<Pair<RouteConfig, Regex>> =
        routes.map { it to patternCompiler(it.topicPattern) }

    /**
     * Routes a topic to its destination, including any configured transforms.
     *
     * @param topic The message topic to route
     * @param payload Optional payload for dynamic routing key substitution
     * @return RoutingResult with destination and transforms, or null if no route matches
     */
    fun route(topic: String, payload: JsonElement? = null): RoutingResult? {
        val matchedRoute = compiledRoutes.firstOrNull { (_, regex) -> regex.matches(topic) }?.first
        return matchedRoute?.let {
            val destination = destinations[it.destination] ?: return null
            val template = it.routingKeyTemplate
            val routingKey = when {
                template == null -> null
                payload != null -> {
                    val missingFieldDefault = it.routingKeyMissingFieldDefault
                    val renderer = if (missingFieldDefault != null) {
                        RoutingKeyRenderer(missingFieldDefault)
                    } else {
                        routingKeyRenderer
                    }
                    renderer.render(template, topic, payload)
                }
                else -> renderLegacyTemplate(template, topic)
            }
            RoutingResult(
                destination = destination,
                routingKey = routingKey,
                routeTransform = it.transform,
                destinationTransform = destinationTransforms[it.destination]
            )
        }
    }

    private fun renderLegacyTemplate(template: String, topic: String): String {
        // Simple template rendering: replace {{ topic }} with actual topic
        return template
            .replace("{{ topic }}", topic)
            .replace("{{topic}}", topic)
    }
}

/**
 * Compiles a topic glob pattern into an anchored regular expression.
 *
 * `*` matches one dot-separated segment. `**` matches anything. Every literal part of the pattern
 * goes through [Regex.escape], so a metacharacter in the pattern stays literal. See F-026.
 *
 * @param pattern The topic glob pattern
 * @return The anchored regular expression for the pattern
 */
fun compileTopicPattern(pattern: String): Regex {
    val builder = StringBuilder()
    var literalStart = 0
    var index = 0
    while (index < pattern.length) {
        if (pattern[index] != '*') {
            index++
            continue
        }
        if (index > literalStart) {
            builder.append(Regex.escape(pattern.substring(literalStart, index)))
        }
        if (index + 1 < pattern.length && pattern[index + 1] == '*') {
            builder.append(".*")
            index += 2
        } else {
            builder.append("[^.]+")
            index += 1
        }
        literalStart = index
    }
    if (literalStart < pattern.length) {
        builder.append(Regex.escape(pattern.substring(literalStart)))
    }
    return Regex("^$builder$")
}
