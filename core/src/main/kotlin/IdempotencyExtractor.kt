package org.nxtspec

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads values out of a message payload with JSONPath.
 *
 * The payload becomes a [DocumentContext] once per message. A parse per configured path costs a
 * full serialise and parse for every path. See F-025.
 *
 * @property parser Builds the document context for a payload. A test can replace it.
 */
class IdempotencyExtractor(
    private val parser: (JsonElement) -> DocumentContext = { payload ->
        JsonPath.parse(Json.encodeToString(JsonElement.serializer(), payload))
    }
) {
    /**
     * Reads one path out of a payload.
     *
     * @param payload The message payload
     * @param jsonPath The JSONPath expression to read
     * @return The value as a string, or a failure with an [ExtractionException]
     */
    fun extract(payload: JsonElement, jsonPath: String): Result<String> = try {
        read(parser(payload), jsonPath)
    } catch (e: Exception) {
        Result.failure(ExtractionException("Extraction failed: ${e.message}", e))
    }

    /**
     * Reads every configured path out of a payload with one parse.
     *
     * @param payload The message payload
     * @param paths A map of caller key to JSONPath expression
     * @return A map of caller key to value. A path that fails maps to null.
     */
    fun extractAll(payload: JsonElement, paths: Map<String, String>): Map<String, String?> {
        if (paths.isEmpty()) {
            return emptyMap()
        }
        val documentContext = try {
            parser(payload)
        } catch (e: Exception) {
            return paths.mapValues { null }
        }
        return paths.mapValues { (_, path) -> read(documentContext, path).getOrNull() }
    }

    private fun read(documentContext: DocumentContext, jsonPath: String): Result<String> = try {
        val compiled = JsonPath.compile(jsonPath)
        val value: Any? = documentContext.read(compiled)
        if (compiled.isDefinite) {
            fromDefinite(value, jsonPath)
        } else {
            fromIndefinite(value, jsonPath)
        }
    } catch (e: PathNotFoundException) {
        Result.failure(ExtractionException("Path not found: $jsonPath"))
    } catch (e: Exception) {
        Result.failure(ExtractionException("Extraction failed: ${e.message}", e))
    }

    /** Reads the one node of a definite path. A JSON null is not a value. */
    private fun fromDefinite(value: Any?, jsonPath: String): Result<String> = if (value == null) {
        Result.failure(ExtractionException("Path not found: $jsonPath"))
    } else {
        Result.success(value.toString())
    }

    /**
     * Reads the result of an indefinite path. See the fourth review gate.
     *
     * An indefinite path, such as `$..orderId`, returns a list. Jayway JsonPath throws nothing
     * for a list with no element, so a message that matches no node once produced the literal
     * key `[]`. Every later message then hit that key and the caller got a duplicate answer for
     * a message that QueueBox never stored.
     *
     * The rules are:
     * - No match is a failure. The caller keeps the message.
     * - One match unwraps to the element, so `$..orderId` and `$.data.orderId` agree.
     * - More than one match is ambiguous and is a failure. QueueBox picks no node for the caller.
     */
    private fun fromIndefinite(value: Any?, jsonPath: String): Result<String> {
        val matches = value as? List<*>
            ?: return Result.failure(
                ExtractionException("Path did not return a list of matches: $jsonPath")
            )
        return when (matches.size) {
            0 -> Result.failure(ExtractionException("Path matched no node: $jsonPath"))
            1 -> fromDefinite(matches.first(), jsonPath)
            else -> Result.failure(
                ExtractionException(
                    "Path matched ${matches.size} nodes and is ambiguous: $jsonPath"
                )
            )
        }
    }

    companion object {
        /**
         * Reports whether a JSONPath expression matches at most one node.
         *
         * A configuration validator uses this to refuse an indefinite path where the product
         * needs one deterministic value.
         *
         * @throws com.jayway.jsonpath.InvalidPathException if the expression does not parse
         */
        fun isDefinitePath(jsonPath: String): Boolean = JsonPath.compile(jsonPath).isDefinite
    }
}
