package org.nxtspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract test for [RoutingKeyRenderer].
 *
 * This test enumerates every placeholder form documented in the README "Routing Key Templates"
 * section. It is the source of truth for that section: any change to the supported placeholder
 * syntax must update this test and the README together.
 */
class RoutingKeyTemplateContractTest {

    private val defaultValue = "default"
    private val renderer = RoutingKeyRenderer(defaultValue)

    private val payload: JsonElement = Json.parseToJsonElement(
        """
        {
          "region": "eu",
          "customer": {
            "region": "de"
          }
        }
        """.trimIndent()
    )

    data class Case(
        val description: String,
        val template: String,
        val expected: String
    )

    private val cases = listOf(
        Case(
            description = "topic with surrounding spaces",
            template = "{{ topic }}",
            expected = "orders.created"
        ),
        Case(
            description = "topic with no spaces",
            template = "{{topic}}",
            expected = "orders.created"
        ),
        Case(
            description = "top-level payload field",
            template = "{{ payload.region }}",
            expected = "eu"
        ),
        Case(
            description = "nested field via data prefix",
            template = "{{ data.customer.region }}",
            expected = "de"
        ),
        Case(
            description = "missing payload field falls back to default",
            template = "{{ payload.missingField }}",
            expected = defaultValue
        ),
        Case(
            description = "unknown bare name falls back to default",
            template = "{{ region }}",
            expected = defaultValue
        )
    )

    @Test
    fun `renders every documented placeholder form to its exact expected output`() {
        for (case in cases) {
            val actual = renderer.render(case.template, topic = "orders.created", payload = payload)
            assertEquals(
                case.expected,
                actual,
                "Case '${case.description}' with template '${case.template}' rendered '$actual', " +
                    "expected '${case.expected}'"
            )
        }
    }
}
