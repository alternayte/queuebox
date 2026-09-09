package org.nxtspec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageRouterTest {

    private fun createRouter(vararg pairs: Pair<String, String>): MessageRouter {
        val destinations = pairs.map { (_, destName) ->
            destName to Destination.Http(
                name = destName,
                baseUrl = "http://localhost:8080"
            )
        }.toMap()

        val routes = pairs.map { (pattern, destName) ->
            RouteConfig(topicPattern = pattern, destination = destName)
        }

        return MessageRouter(routes, destinations)
    }

    @Test
    fun `should match exact topic when exact pattern`() {
        val router = createRouter("order.created" to "http-dest")

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("http-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should not match different topic when exact pattern`() {
        val router = createRouter("order.created" to "http-dest")

        val result = router.route(OutboxMessage(topic = "order.cancelled", payload = JsonObject(emptyMap())))

        assertNull(result)
    }

    @Test
    fun `should match glob pattern when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        assertNotNull(router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "order.cancelled", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "order.shipped", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should not match different prefix when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        assertNull(router.route(OutboxMessage(topic = "user.created", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "payment.processed", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should not match multi-segment when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        // Single wildcard should not match multiple segments
        assertNull(router.route(OutboxMessage(topic = "order.item.created", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should match multi-segment when double wildcard`() {
        val router = createRouter("events.**" to "events-dest")

        assertNotNull(router.route(OutboxMessage(topic = "events.user.created", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "events.order.item.added", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "events.a.b.c.d", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should match single segment when double wildcard`() {
        val router = createRouter("events.**" to "events-dest")

        // Double wildcard should also match single segments
        assertNotNull(router.route(OutboxMessage(topic = "events.created", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should return first match when multiple routes match`() {
        val router = createRouter(
            "order.created" to "specific-dest",
            "order.*" to "general-dest"
        )

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("specific-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should match second route when first does not match`() {
        val router = createRouter(
            "order.created" to "specific-dest",
            "order.*" to "general-dest"
        )

        val result = router.route(OutboxMessage(topic = "order.cancelled", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("general-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should return null when no route matches`() {
        val router = createRouter("order.*" to "order-dest")

        val result = router.route(OutboxMessage(topic = "user.created", payload = JsonObject(emptyMap())))

        assertNull(result)
    }

    @Test
    fun `should return null when routes list is empty`() {
        val router = MessageRouter(emptyList(), emptyMap())

        val result = router.route(OutboxMessage(topic = "any.topic", payload = JsonObject(emptyMap())))

        assertNull(result)
    }

    @Test
    fun `should return null when destination not found`() {
        val routes = listOf(RouteConfig(topicPattern = "order.*", destination = "missing-dest"))
        val router = MessageRouter(routes, emptyMap())

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNull(result)
    }

    @Test
    fun `should return no routing key when no template specified`() {
        // F-004: a null routing key lets the destination apply its own fallback template.
        val router = createRouter("order.*" to "dest")

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertNull(result.routingKey)
    }

    @Test
    fun `should replace topic placeholder in routing key template`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "routed.{{ topic }}.events"
            )
        )
        val router = MessageRouter(routes, destinations)

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("routed.order.created.events", result.routingKey)
    }

    @Test
    fun `should replace topic placeholder without spaces`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "routed.{{topic}}.events"
            )
        )
        val router = MessageRouter(routes, destinations)

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("routed.order.created.events", result.routingKey)
    }

    @Test
    fun `should handle pattern with wildcard in middle`() {
        val router = createRouter("order.*.completed" to "dest")

        assertNotNull(router.route(OutboxMessage(topic = "order.123.completed", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "order.abc.completed", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.completed", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.123.456.completed", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should handle multiple wildcards in pattern`() {
        val router = createRouter("*.*.created" to "dest")

        assertNotNull(router.route(OutboxMessage(topic = "order.item.created", payload = JsonObject(emptyMap()))))
        assertNotNull(router.route(OutboxMessage(topic = "user.profile.created", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should be case sensitive`() {
        val router = createRouter("Order.Created" to "dest")

        assertNotNull(router.route(OutboxMessage(topic = "Order.Created", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "ORDER.CREATED", payload = JsonObject(emptyMap()))))
    }

    // Payload-based routing key tests

    @Test
    fun `should use payload field in routing key template`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "events.{{ payload.region }}.{{ topic }}"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {
            put("region", JsonPrimitive("us-east"))
        }

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("events.us-east.order.created", result.routingKey)
    }

    @Test
    fun `should render the topic placeholder for a row with an empty payload`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "routed.{{ topic }}"
            )
        )
        val router = MessageRouter(routes, destinations)

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("routed.order.created", result.routingKey)
    }

    @Test
    fun `should use empty string default for missing payload field`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "events.{{ payload.missing }}.{{ topic }}"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {}

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("events..order.created", result.routingKey)
    }

    @Test
    fun `should use custom default for missing payload field`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "events.{{ payload.region }}.{{ topic }}",
                routingKeyMissingFieldDefault = "default"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {}

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("events.default.order.created", result.routingKey)
    }

    @Test
    fun `should extract nested payload fields for routing`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "events.{{ payload.data.region }}"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("region", JsonPrimitive("eu-west"))
                }
            )
        }

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("events.eu-west", result.routingKey)
    }

    @Test
    fun `should support data prefix as payload alias`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "events.{{ data.eventType }}"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {
            put("eventType", JsonPrimitive("order.created"))
        }

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("events.order.created", result.routingKey)
    }

    @Test
    fun `should preserve backward compatibility - existing topic patterns work with payload`() {
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost")
        )
        val routes = listOf(
            RouteConfig(
                topicPattern = "order.*",
                destination = "dest",
                routingKeyTemplate = "routed.{{ topic }}.events"
            )
        )
        val router = MessageRouter(routes, destinations)
        val payload = buildJsonObject {
            put("ignored", JsonPrimitive("field"))
        }

        val result = router.route(OutboxMessage(topic = "order.created", payload = payload))

        assertNotNull(result)
        assertEquals("routed.order.created.events", result.routingKey)
    }

    // --- F-026: precompiled, escaped and anchored topic patterns ---

    @Test
    fun `should match topic that contains regex metacharacters`() {
        val router = createRouter("order.a+b" to "plus-dest")

        assertNotNull(router.route(OutboxMessage(topic = "order.a+b", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.aab", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.ab", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should match topic that contains parentheses and brackets`() {
        val parens = createRouter("order.(x)" to "paren-dest")
        assertNotNull(parens.route(OutboxMessage(topic = "order.(x)", payload = JsonObject(emptyMap()))))
        assertNull(parens.route(OutboxMessage(topic = "order.x", payload = JsonObject(emptyMap()))))

        val brackets = createRouter("order.[y]" to "bracket-dest")
        assertNotNull(brackets.route(OutboxMessage(topic = "order.[y]", payload = JsonObject(emptyMap()))))
        assertNull(brackets.route(OutboxMessage(topic = "order.y", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should match topic that contains the legacy placeholder token`() {
        val literal = createRouter("order.\u00A7\u00A7\u00A7" to "token-dest")
        assertNotNull(
            literal.route(OutboxMessage(topic = "order.\u00A7\u00A7\u00A7", payload = JsonObject(emptyMap())))
        )
        assertNull(literal.route(OutboxMessage(topic = "order.anything.else", payload = JsonObject(emptyMap()))))

        val withWildcard = createRouter("\u00A7\u00A7\u00A7.**" to "token-glob-dest")
        assertNotNull(
            withWildcard.route(OutboxMessage(topic = "\u00A7\u00A7\u00A7.a.b", payload = JsonObject(emptyMap())))
        )
        assertNull(withWildcard.route(OutboxMessage(topic = "other.a.b", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should anchor the pattern so a partial match is rejected`() {
        val router = createRouter("order.created" to "dest")

        assertNull(router.route(OutboxMessage(topic = "prefix.order.created", payload = JsonObject(emptyMap()))))
        assertNull(router.route(OutboxMessage(topic = "order.created.suffix", payload = JsonObject(emptyMap()))))
    }

    @Test
    fun `should compile each topic pattern once for many routed messages`() {
        var compileCount = 0
        val destinations = mapOf(
            "dest" to Destination.Http(name = "dest", baseUrl = "http://localhost:8080")
        )
        val routes = listOf(
            RouteConfig(topicPattern = "order.*", destination = "dest"),
            RouteConfig(topicPattern = "user.**", destination = "dest")
        )
        val router = MessageRouter(
            routes = routes,
            destinations = destinations,
            patternCompiler = { pattern ->
                compileCount++
                compileTopicPattern(pattern)
            }
        )

        repeat(100) { index ->
            router.route(OutboxMessage(topic = "order.created.$index", payload = JsonObject(emptyMap())))
            router.route(OutboxMessage(topic = "user.updated.$index", payload = JsonObject(emptyMap())))
        }

        assertEquals(2, compileCount)
    }

    // --- F-091: the router resolves the destination's own address template per row ---

    @Test
    fun `should render the RabbitMQ exchange template with the row aggregate type`() {
        val destinations = mapOf(
            "dest" to Destination.RabbitMQ(
                name = "dest",
                url = "amqp://localhost",
                exchange = "public.orders.{{ aggregateType }}.v1",
                exchangeType = "topic"
            )
        )
        val routes = listOf(RouteConfig(topicPattern = "order.*", destination = "dest"))
        val router = MessageRouter(routes, destinations)

        val result = router.route(
            OutboxMessage(topic = "order.created", aggregateType = "Task", payload = JsonObject(emptyMap()))
        )

        assertNotNull(result)
        assertEquals("public.orders.Task.v1", result.resolvedAddress)
    }

    @Test
    fun `should render an empty resolvedAddress when the row has no aggregate type`() {
        val destinations = mapOf(
            "dest" to Destination.RabbitMQ(
                name = "dest",
                url = "amqp://localhost",
                exchange = "{{ aggregateType }}",
                exchangeType = "topic"
            )
        )
        val routes = listOf(RouteConfig(topicPattern = "order.*", destination = "dest"))
        val router = MessageRouter(routes, destinations)

        val result = router.route(OutboxMessage(topic = "order.created", payload = JsonObject(emptyMap())))

        assertNotNull(result)
        assertEquals("", result.resolvedAddress)
    }
}
