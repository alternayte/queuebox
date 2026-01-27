package org.nxtspec

data class RoutingResult(
    val destination: Destination,
    val routingKey: String
)

class MessageRouter(
    private val routes: List<RouteConfig>,
    private val destinations: Map<String, Destination>
) {
    fun route(topic: String): RoutingResult? {
        val matchedRoute = routes.firstOrNull { matchesPattern(topic, it.topicPattern) }
        return matchedRoute?.let {
            val destination = destinations[it.destination] ?: return null
            RoutingResult(
                destination = destination,
                routingKey = renderTemplate(it.routingKeyTemplate ?: topic, topic)
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

    private fun renderTemplate(template: String, topic: String): String {
        // Simple template rendering: replace {{ topic }} with actual topic
        return template
            .replace("{{ topic }}", topic)
            .replace("{{topic}}", topic)
    }
}
