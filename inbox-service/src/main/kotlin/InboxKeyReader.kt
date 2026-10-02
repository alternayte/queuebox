package org.nxtspec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.nxtspec.logging.logger
import org.nxtspec.transform.TransformEngine
import kotlin.time.Instant

/**
 * How a source reads one inbox key from the body.
 *
 * @property name The key without its suffix: `idempotencyKey`, `aggregateId` or `eventType`
 * @property expression The JSONata expression of the key. Issue #83.
 * @property paths The JSONPaths of the key, in the order to try them. Issue #85.
 */
data class BodyKey(val name: String, val expression: String? = null, val paths: KeyPaths? = null)

/**
 * Where a source reads the publish time of a message. Issue #91.
 *
 * @property header The header that carries the time. The name matches in any letter case.
 * @property paths The JSONPaths of the time in the body, in the order to try them.
 */
data class PublishTimeKeys(val header: String? = null, val paths: KeyPaths? = null) {
    /** A source that sets neither key keeps the receipt time, and reads nothing. */
    val enabled: Boolean get() = header != null || paths != null
}

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
     * The publish time of one message, or null when the store must use the receipt time.
     *
     * The header comes first, then the paths, then [brokerTime], which is the time that the
     * broker protocol carries. A source that sets neither key gets null, whatever the message
     * holds. A value that does not parse counts as missing.
     *
     * @param payload The parsed body, or null when the body is not JSON
     */
    fun publishedAt(
        source: String,
        payload: JsonElement?,
        headers: Map<String, String>,
        keys: PublishTimeKeys,
        brokerTime: Instant? = null
    ): Instant? {
        if (!keys.enabled) return null
        val fromHeader = keys.header?.trim()?.let { wanted ->
            headers.entries.lastOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
        }
        val fromBody = keys.paths?.takeIf { payload != null }?.let { paths ->
            extractor.extractAll(payload!!, mapOf(PUBLISH_TIME to paths.paths))[PUBLISH_TIME]
        }
        return listOfNotNull(fromHeader, fromBody).firstNotNullOfOrNull { parse(source, it) } ?: brokerTime
    }

    private fun parse(source: String, value: String): Instant? = parsePublishTime(value) ?: run {
        log.warn("The publish time '{}' of a message of source '{}' is not a time, so it is ignored.", value, source)
        null
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

    companion object {
        private const val PUBLISH_TIME = "publishTime"

        /** A number below this value is seconds since the epoch. A larger one is milliseconds. */
        private const val SECONDS_LIMIT = 100_000_000_000L

        /** 9999-12-31T23:59:59Z. SQL Server stores no later time. */
        private const val MAX_EPOCH_SECONDS = 253_402_300_799L

        /**
         * Reads a publish time. The value is an ISO-8601 time with an offset, or a whole number
         * of seconds or milliseconds since the epoch. A time before 1970 or after 9999 is not a
         * publish time.
         */
        fun parsePublishTime(value: String): Instant? {
            val text = value.trim()
            val number = text.toLongOrNull()
            val instant = when {
                number == null -> Instant.parseOrNull(text)
                number < 0 -> null
                number < SECONDS_LIMIT -> Instant.fromEpochSeconds(number)
                else -> Instant.fromEpochMilliseconds(number)
            }
            return instant?.takeIf { it.epochSeconds in 0..MAX_EPOCH_SECONDS }
        }
    }
}
