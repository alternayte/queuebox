package org.nxtspec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.logging.logger
import org.nxtspec.transform.TransformEngine

/**
 * How a source reads one inbox key from the body.
 *
 * @property name The key without its suffix: `idempotencyKey`, `aggregateId` or `eventType`
 * @property expression The JSONata expression of the key. Issue #83.
 * @property paths The JSONPaths of the key, in the order to try them. Issue #85.
 */
data class BodyKey(val name: String, val expression: String? = null, val paths: KeyPaths? = null)

/**
 * Reads the idempotency key, the aggregate ID and the event type out of a message body.
 *
 * The expression of a key comes first. The paths are the fallback, in their order, and the first
 * one that gives a value wins. Every read uses the ORIGINAL payload, before the source transform.
 */
class InboxKeyReader(
    private val extractor: IdempotencyExtractor = IdempotencyExtractor(),
    private val engine: TransformEngine = TransformEngine()
) {
    private val log = logger<InboxKeyReader>()

    /** The value of one key, or null when neither its expression nor a path gives one. */
    fun read(source: String, payload: JsonElement, key: BodyKey): String? =
        readAll(source, payload, listOf(key))[key.name]

    /**
     * The value of every key in [keys], by key name. A key with no value maps to null.
     *
     * The paths of every key that still needs one are read with one parse. See F-025.
     */
    fun readAll(source: String, payload: JsonElement, keys: List<BodyKey>): Map<String, String?> {
        val fromExpressions = keys.mapNotNull { key ->
            key.expression?.let { evaluate(source, payload, key.name, it) }?.let { key.name to it }
        }.toMap()
        val paths = keys
            .filter { it.name !in fromExpressions && it.paths != null }
            .associate { it.name to it.paths!!.paths }
        return keys.associate { it.name to null } + extractor.extractAll(payload, paths) + fromExpressions
    }

    /**
     * Evaluates one key expression. A failure is no value, so one bad message cannot stop the
     * source, and the path of the key still applies.
     */
    private fun evaluate(source: String, payload: JsonElement, name: String, expression: String): String? =
        engine.evaluate(expression, payload).fold(
            onSuccess = ::keyValue,
            onFailure = { error ->
                log.warn(
                    "The {}Expression of source '{}' failed, so it gives no value: {}",
                    name,
                    source,
                    error.message
                )
                null
            }
        )

    /**
     * The text of an expression result. A string, a number and a boolean are values. An object,
     * an array and null are not, and neither is a blank string, which would merge unrelated
     * messages under one key.
     */
    private fun keyValue(result: JsonElement): String? {
        if (result !is JsonPrimitive || result is JsonNull) return null
        val text = if (result.isString || result.content == "true" || result.content == "false") {
            result.content
        } else {
            // JSONata computes in doubles. `515725.0` and `515725` are the same key.
            result.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: result.content
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }
}
