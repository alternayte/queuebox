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
 * @property routes List of route configurations defining topic-to-destination mappings
 * @property destinations Map of destination name to Destination instances
 * @property destinationTransforms Map of destination name to optional transform config
 * @property routingKeyRenderer Renderer for routing key templates with payload field substitution
 */
class MessageRouter(
    private val routes: List<RouteConfig>,
    private val destinations: Map<String, Destination>,
    private val destinationTransforms: Map<String, TransformConfig?> = emptyMap(),
    private val routingKeyRenderer: RoutingKeyRenderer = RoutingKeyRenderer()
) {
    /**
     * Routes a topic to its destination, including any configured transforms.
     *
     * @param topic The message topic to route
     * @param payload Optional payload for dynamic routing key substitution
     * @return RoutingResult with destination and transforms, or null if no route matches
     */
    fun route(topic: String, payload: JsonElement? = null): RoutingResult? {
        val matchedRoute = routes.firstOrNull { matchesPattern(topic, it.topicPattern) }
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

    private fun matchesPattern(topic: String, pattern: String): Boolean {
        // Convert glob pattern to regex
        // Order matters: replace "**" before "*" to avoid double conversion
        val regex = pattern
            .replace(".", "\\.")      // Escape literal dots
            .replace("**", "§§§")     // Temporary placeholder for **
            .replace("*", "[^.]+")    // Single wildcard: matches one segment
            .replace("§§§", ".*")     // Multi-segment wildcard: matches anything
            .toRegex()
        return regex.matches(topic)
    }

    private fun renderLegacyTemplate(template: String, topic: String): String {
        // Simple template rendering: replace {{ topic }} with actual topic
        return template
            .replace("{{ topic }}", topic)
            .replace("{{topic}}", topic)
    }
}
