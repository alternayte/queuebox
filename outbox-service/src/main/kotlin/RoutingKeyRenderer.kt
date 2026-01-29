package org.nxtspec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders routing key templates with dynamic field substitution.
 *
 * Supports mustache-style placeholders:
 * - {{ topic }} - The message topic
 * - {{ payload.fieldName }} - Access payload fields (JSON path)
 * - {{ data.nested.field }} - Access nested payload fields (alias for payload)
 *
 * @property defaultValue Value to use when a field is missing (default: empty string)
 */
class RoutingKeyRenderer(
    private val defaultValue: String = ""
) {
    // Pattern to match {{ field }} placeholders with optional spaces
    private val placeholderPattern = Regex("""\{\{\s*([^}]+?)\s*}}""")

    /**
     * Renders a routing key template by substituting placeholders.
     *
     * @param template The routing key template with placeholders
     * @param topic The message topic
     * @param payload The message payload for field extraction
     * @return The rendered routing key with all placeholders substituted
     */
    fun render(template: String, topic: String, payload: JsonElement): String {
        return placeholderPattern.replace(template) { match ->
            val field = match.groupValues[1].trim()
            resolveField(field, topic, payload)
        }
    }

    private fun resolveField(field: String, topic: String, payload: JsonElement): String {
        return when {
            field == "topic" -> topic
            field.startsWith("payload.") -> {
                extractPayloadField(payload, field.removePrefix("payload.")) ?: defaultValue
            }
            field.startsWith("data.") -> {
                extractPayloadField(payload, field.removePrefix("data.")) ?: defaultValue
            }
            else -> defaultValue
        }
    }

    private fun extractPayloadField(payload: JsonElement, path: String): String? {
        val parts = path.split(".")
        var current: JsonElement? = payload

        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part]
                else -> null
            }
            if (current == null) break
        }

        return when (current) {
            is JsonPrimitive -> current.content
            is JsonObject, is JsonArray -> current.toString()
            null -> null
        }
    }
}
