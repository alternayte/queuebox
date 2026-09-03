package org.nxtspec

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
    fun extract(payload: JsonElement, jsonPath: String): Result<String> {
        return try {
            read(parser(payload), jsonPath)
        } catch (e: Exception) {
            Result.failure(ExtractionException("Extraction failed: ${e.message}", e))
        }
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

    private fun read(documentContext: DocumentContext, jsonPath: String): Result<String> {
        return try {
            val value: Any = documentContext.read(jsonPath)
            Result.success(value.toString())
        } catch (e: PathNotFoundException) {
            Result.failure(ExtractionException("Path not found: $jsonPath"))
        } catch (e: Exception) {
            Result.failure(ExtractionException("Extraction failed: ${e.message}", e))
        }
    }
}
