package org.nxtspec

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingKeyRendererTest {

    private val renderer = RoutingKeyRenderer()

    @Test
    fun `should replace topic placeholder`() {
        val result = renderer.render("events.{{ topic }}", "order.created", buildJsonObject {})

        assertEquals("events.order.created", result)
    }

    @Test
    fun `should replace topic placeholder without spaces`() {
        val result = renderer.render("events.{{topic}}", "order.created", buildJsonObject {})

        assertEquals("events.order.created", result)
    }

    @Test
    fun `should extract top-level payload field`() {
        val payload = buildJsonObject {
            put("eventType", JsonPrimitive("order.created"))
        }

        val result = renderer.render("events.{{ payload.eventType }}", "topic", payload)

        assertEquals("events.order.created", result)
    }

    @Test
    fun `should extract nested payload field`() {
        val payload = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("region", JsonPrimitive("us-east"))
                }
            )
        }

        val result = renderer.render("events.{{ payload.data.region }}", "topic", payload)

        assertEquals("events.us-east", result)
    }

    @Test
    fun `should extract deeply nested payload field`() {
        val payload = buildJsonObject {
            put(
                "a",
                buildJsonObject {
                    put(
                        "b",
                        buildJsonObject {
                            put(
                                "c",
                                buildJsonObject {
                                    put("d", JsonPrimitive("value"))
                                }
                            )
                        }
                    )
                }
            )
        }

        val result = renderer.render("{{ payload.a.b.c.d }}", "topic", payload)

        assertEquals("value", result)
    }

    @Test
    fun `should support data prefix as alias for payload`() {
        val payload = buildJsonObject {
            put("region", JsonPrimitive("eu-west"))
        }

        val result = renderer.render("events.{{ data.region }}", "topic", payload)

        assertEquals("events.eu-west", result)
    }

    @Test
    fun `should return empty string for missing field by default`() {
        val payload = buildJsonObject {}

        val result = renderer.render("events.{{ payload.missing }}.suffix", "topic", payload)

        assertEquals("events..suffix", result)
    }

    @Test
    fun `should return custom default for missing field`() {
        val customRenderer = RoutingKeyRenderer(defaultValue = "unknown")
        val payload = buildJsonObject {}

        val result = customRenderer.render("events.{{ payload.missing }}", "topic", payload)

        assertEquals("events.unknown", result)
    }

    @Test
    fun `should handle multiple placeholders in same template`() {
        val payload = buildJsonObject {
            put("eventType", JsonPrimitive("order.created"))
            put("region", JsonPrimitive("us-east"))
        }

        val result = renderer.render(
            "{{ payload.region }}.{{ topic }}.{{ payload.eventType }}",
            "events",
            payload
        )

        assertEquals("us-east.events.order.created", result)
    }

    @Test
    fun `should handle placeholder with extra whitespace`() {
        val payload = buildJsonObject {
            put("field", JsonPrimitive("value"))
        }

        val result = renderer.render("{{  payload.field  }}", "topic", payload)

        assertEquals("value", result)
    }

    @Test
    fun `should convert number to string`() {
        val payload = buildJsonObject {
            put("count", JsonPrimitive(42))
        }

        val result = renderer.render("count.{{ payload.count }}", "topic", payload)

        assertEquals("count.42", result)
    }

    @Test
    fun `should convert boolean to string`() {
        val payload = buildJsonObject {
            put("enabled", JsonPrimitive(true))
        }

        val result = renderer.render("status.{{ payload.enabled }}", "topic", payload)

        assertEquals("status.true", result)
    }

    @Test
    fun `should convert object to string representation`() {
        val payload = buildJsonObject {
            put(
                "nested",
                buildJsonObject {
                    put("key", JsonPrimitive("value"))
                }
            )
        }

        val result = renderer.render("obj.{{ payload.nested }}", "topic", payload)

        assertEquals("""obj.{"key":"value"}""", result)
    }

    @Test
    fun `should convert array to string representation`() {
        val payload = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(2))
                }
            )
        }

        val result = renderer.render("arr.{{ payload.items }}", "topic", payload)

        assertEquals("arr.[1,2]", result)
    }

    @Test
    fun `should return template unchanged when no placeholders`() {
        val payload = buildJsonObject {}

        val result = renderer.render("static.routing.key", "topic", payload)

        assertEquals("static.routing.key", result)
    }

    @Test
    fun `should return empty string for empty template`() {
        val payload = buildJsonObject {}

        val result = renderer.render("", "topic", payload)

        assertEquals("", result)
    }

    @Test
    fun `should return default for missing intermediate object`() {
        val payload = buildJsonObject {
            put("other", JsonPrimitive("value"))
        }

        val result = renderer.render("{{ payload.missing.nested }}", "topic", payload)

        assertEquals("", result)
    }

    @Test
    fun `should handle special characters in field values`() {
        val payload = buildJsonObject {
            put("path", JsonPrimitive("/api/v1/users"))
        }

        val result = renderer.render("route.{{ payload.path }}", "topic", payload)

        assertEquals("route./api/v1/users", result)
    }

    @Test
    fun `should handle mixed topic and payload placeholders`() {
        val payload = buildJsonObject {
            put("region", JsonPrimitive("us-east"))
        }

        val result = renderer.render("{{ topic }}.{{ payload.region }}", "orders", payload)

        assertEquals("orders.us-east", result)
    }

    @Test
    fun `should return default for unrecognized placeholder prefix`() {
        val payload = buildJsonObject {
            put("field", JsonPrimitive("value"))
        }

        val result = renderer.render("{{ unknown.field }}", "topic", payload)

        assertEquals("", result)
    }

    @Test
    fun `should handle JsonArray at root level`() {
        // When payload is an array (not an object), field extraction should return default
        val payload = buildJsonArray {
            add(JsonPrimitive("item1"))
            add(JsonPrimitive("item2"))
        }

        val result = renderer.render("events.{{ payload.field }}.suffix", "topic", payload)

        assertEquals("events..suffix", result)
    }

    @Test
    fun `should handle null JsonElement values in nested path`() {
        // When a field exists but the value is JSON null, it gets converted to "null" string
        val payload = buildJsonObject {
            put(
                "outer",
                buildJsonObject {
                    put("nullField", kotlinx.serialization.json.JsonNull)
                }
            )
        }

        val result = renderer.render("prefix.{{ payload.outer.nullField }}.suffix", "topic", payload)

        // JsonNull is a JsonPrimitive with content "null"
        assertEquals("prefix.null.suffix", result)
    }

    @Test
    fun `should handle path traversal through null value`() {
        // When trying to traverse through a null value
        val payload = buildJsonObject {
            put("nullObject", kotlinx.serialization.json.JsonNull)
        }

        val result = renderer.render("{{ payload.nullObject.nested }}", "topic", payload)

        assertEquals("", result)
    }

    @Test
    fun `should handle array in path`() {
        // When an intermediate value in the path is an array, traversal should stop
        val payload = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("field", JsonPrimitive("value"))
                        }
                    )
                }
            )
        }

        // Can't traverse into array items with simple path
        val result = renderer.render("{{ payload.items.field }}", "topic", payload)

        assertEquals("", result)
    }
}
