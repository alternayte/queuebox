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
 * - {{ key }} - The message key of the outbox row
 * - {{ aggregateType }} - The aggregate type of the outbox row
 * - {{ payload.fieldName }} - Access payload fields (JSON path)
 * - {{ data.nested.field }} - Access nested payload fields (alias for payload)
 *
 * @property defaultValue Value to use when a field is missing (default: empty string)
 */
class RoutingKeyRenderer(private val defaultValue: String = "") {
    // Pattern to match {{ field }} placeholders with optional spaces
    private val placeholderPattern = Regex("""\{\{\s*([^}]+?)\s*}}""")

    /**
     * The fields of one outbox row that a template can read. F-091.
     *
     * @property topic The message topic.
     * @property key The message key, or null when the row sets no key.
     * @property aggregateType The aggregate type of the row, or null when the row sets none.
     * @property payload The message payload for field extraction.
     */
    data class RowContext(val topic: String, val key: String?, val aggregateType: String?, val payload: JsonElement)

    /**
     * Renders a routing key template by substituting placeholders read from a row.
     *
     * @param template The routing key template with placeholders
     * @param row The outbox row that supplies the field values
     * @return The rendered routing key with all placeholders substituted
     */
    fun render(template: String, row: RowContext): String = placeholderPattern.replace(template) { match ->
        resolveField(match.groupValues[1].trim(), row)
    }

    /**
     * Renders a routing key template by substituting placeholders.
     *
     * This overload is deprecated. It builds a [RowContext] with a null `key` and a null
     * `aggregateType`, then delegates to [render] with a row. A caller must migrate to
     * [render] with a row so a template can read the key and the aggregate type.
     *
     * @param template The routing key template with placeholders
     * @param topic The message topic
     * @param payload The message payload for field extraction
     * @return The rendered routing key with all placeholders substituted
     */
    @Deprecated(
        "Use render(template, row: RowContext) so a template can read the key and the aggregate type.",
        ReplaceWith("render(template, RoutingKeyRenderer.RowContext(topic, null, null, payload))")
    )
    fun render(template: String, topic: String, payload: JsonElement): String =
        render(template, RowContext(topic = topic, key = null, aggregateType = null, payload = payload))

    private fun resolveField(field: String, row: RowContext): String = when {
        field == "topic" -> row.topic
        field == "key" -> row.key ?: defaultValue
        field == "aggregateType" -> row.aggregateType ?: defaultValue
        field.startsWith("payload.") -> {
            extractPayloadField(row.payload, field.removePrefix("payload.")) ?: defaultValue
        }
        field.startsWith("data.") -> {
            extractPayloadField(row.payload, field.removePrefix("data.")) ?: defaultValue
        }
        else -> defaultValue
    }

    companion object {
        /**
         * The template fields a routing key template can read. F-091. A later task uses this
         * set to validate a template at startup, so this set must stay the single source.
         */
        val PERMITTED_TEMPLATE_FIELDS: Set<String> = setOf("topic", "key", "aggregateType")

        /**
         * The prefixes a routing key template can read a nested field from. F-091. A field
         * beginning with one of these prefixes reads from the message payload.
         */
        val PERMITTED_TEMPLATE_FIELD_PREFIXES: Set<String> = setOf("payload.", "data.")
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
