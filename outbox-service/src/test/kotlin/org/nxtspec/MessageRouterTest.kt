package org.nxtspec

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

        val result = router.route("order.created")

        assertNotNull(result)
        assertEquals("http-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should not match different topic when exact pattern`() {
        val router = createRouter("order.created" to "http-dest")

        val result = router.route("order.cancelled")

        assertNull(result)
    }

    @Test
    fun `should match glob pattern when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        assertNotNull(router.route("order.created"))
        assertNotNull(router.route("order.cancelled"))
        assertNotNull(router.route("order.shipped"))
    }

    @Test
    fun `should not match different prefix when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        assertNull(router.route("user.created"))
        assertNull(router.route("payment.processed"))
    }

    @Test
    fun `should not match multi-segment when single wildcard`() {
        val router = createRouter("order.*" to "order-dest")

        // Single wildcard should not match multiple segments
        assertNull(router.route("order.item.created"))
    }

    @Test
    fun `should match multi-segment when double wildcard`() {
        val router = createRouter("events.**" to "events-dest")

        assertNotNull(router.route("events.user.created"))
        assertNotNull(router.route("events.order.item.added"))
        assertNotNull(router.route("events.a.b.c.d"))
    }

    @Test
    fun `should match single segment when double wildcard`() {
        val router = createRouter("events.**" to "events-dest")

        // Double wildcard should also match single segments
        assertNotNull(router.route("events.created"))
    }

    @Test
    fun `should return first match when multiple routes match`() {
        val router = createRouter(
            "order.created" to "specific-dest",
            "order.*" to "general-dest"
        )

        val result = router.route("order.created")

        assertNotNull(result)
        assertEquals("specific-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should match second route when first does not match`() {
        val router = createRouter(
            "order.created" to "specific-dest",
            "order.*" to "general-dest"
        )

        val result = router.route("order.cancelled")

        assertNotNull(result)
        assertEquals("general-dest", (result.destination as Destination.Http).name)
    }

    @Test
    fun `should return null when no route matches`() {
        val router = createRouter("order.*" to "order-dest")

        val result = router.route("user.created")

        assertNull(result)
    }

    @Test
    fun `should return null when routes list is empty`() {
        val router = MessageRouter(emptyList(), emptyMap())

        val result = router.route("any.topic")

        assertNull(result)
    }

    @Test
    fun `should return null when destination not found`() {
        val routes = listOf(RouteConfig(topicPattern = "order.*", destination = "missing-dest"))
        val router = MessageRouter(routes, emptyMap())

        val result = router.route("order.created")

        assertNull(result)
    }

    @Test
    fun `should use topic as routing key when no template specified`() {
        val router = createRouter("order.*" to "dest")

        val result = router.route("order.created")

        assertNotNull(result)
        assertEquals("order.created", result.routingKey)
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

        val result = router.route("order.created")

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

        val result = router.route("order.created")

        assertNotNull(result)
        assertEquals("routed.order.created.events", result.routingKey)
    }

    @Test
    fun `should handle pattern with wildcard in middle`() {
        val router = createRouter("order.*.completed" to "dest")

        assertNotNull(router.route("order.123.completed"))
        assertNotNull(router.route("order.abc.completed"))
        assertNull(router.route("order.completed"))
        assertNull(router.route("order.123.456.completed"))
    }

    @Test
    fun `should handle multiple wildcards in pattern`() {
        val router = createRouter("*.*.created" to "dest")

        assertNotNull(router.route("order.item.created"))
        assertNotNull(router.route("user.profile.created"))
        assertNull(router.route("order.created"))
    }

    @Test
    fun `should be case sensitive`() {
        val router = createRouter("Order.Created" to "dest")

        assertNotNull(router.route("Order.Created"))
        assertNull(router.route("order.created"))
        assertNull(router.route("ORDER.CREATED"))
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

        val result = router.route("order.created", payload)

        assertNotNull(result)
        assertEquals("events.us-east.order.created", result.routingKey)
    }

    @Test
    fun `should use legacy template when payload is null`() {
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

        val result = router.route("order.created", null)

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

        val result = router.route("order.created", payload)

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

        val result = router.route("order.created", payload)

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
            put("data", buildJsonObject {
                put("region", JsonPrimitive("eu-west"))
            })
        }

        val result = router.route("order.created", payload)

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

        val result = router.route("order.created", payload)

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

        val result = router.route("order.created", payload)

        assertNotNull(result)
        assertEquals("routed.order.created.events", result.routingKey)
    }
}
