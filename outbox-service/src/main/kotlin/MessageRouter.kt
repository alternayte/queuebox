package org.nxtspec

/**
 * Result of routing a message to a destination.
 *
 * @property destination The resolved destination to publish to
 * @property routingKey The routing key that the route resolved, or null when the route sets no
 *   routingKeyTemplate. A null value lets the destination apply its own fallback. See F-004.
 * @property resolvedAddress The exchange, topic, or subject that the row resolved from the
 *   destination's address template. A publisher must use this value and never read the
 *   destination's own address field again. See F-091.
 * @property routeTransform Optional transform configured at the route level
 * @property destinationTransform Optional transform configured at the destination level
 */
data class RoutingResult(
    val destination: Destination,
    val routingKey: String?,
    val resolvedAddress: String = "",
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
     * Routes an outbox row to its destination, including any configured transforms and the
     * resolved address for the destination's exchange, topic, or subject. F-091.
     *
     * @param row The outbox row to route
     * @return RoutingResult with destination, resolved address, and transforms, or null if no
     *   route matches
     */
    fun route(row: OutboxMessage): RoutingResult? {
        val matchedRoute = compiledRoutes.firstOrNull { (_, regex) -> regex.matches(row.topic) }?.first
        return matchedRoute?.let {
            val destination = destinations[it.destination] ?: return null
            val context = RoutingKeyRenderer.RowContext(row.topic, row.key, row.aggregateType, row.payload)
            val template = it.routingKeyTemplate
            val routingKey = when (template) {
                null -> null
                else -> {
                    val missingFieldDefault = it.routingKeyMissingFieldDefault
                    val renderer = if (missingFieldDefault != null) {
                        RoutingKeyRenderer(missingFieldDefault)
                    } else {
                        routingKeyRenderer
                    }
                    renderer.render(template, context)
                }
            }
            RoutingResult(
                destination = destination,
                routingKey = routingKey,
                resolvedAddress = resolveAddress(destination, context),
                routeTransform = it.transform,
                destinationTransform = destinationTransforms[it.destination]
            )
        }
    }

    /**
     * Renders the destination's own address template against the row. F-091. The address is the
     * RabbitMQ exchange, the Kafka topic, the NATS subject, or the HTTP path, depending on the
     * destination type.
     */
    private fun resolveAddress(destination: Destination, context: RoutingKeyRenderer.RowContext): String {
        val template = when (destination) {
            is Destination.RabbitMQ -> destination.exchange
            is Destination.Kafka -> destination.topic
            is Destination.Nats -> destination.subject
            is Destination.Http -> destination.path
        }
        return routingKeyRenderer.render(template, context)
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
