package org.nxtspec

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class IdempotencyExtractor {
    fun extract(payload: JsonElement, jsonPath: String): Result<String> {
        return try {
            val jsonString = Json.encodeToString(payload)
            val documentContext = JsonPath.parse(jsonString)
            val value: Any = documentContext.read(jsonPath)
            Result.success(value.toString())
        } catch (e: PathNotFoundException) {
            Result.failure(ExtractionException("Path not found: $jsonPath"))
        } catch (e: Exception) {
            Result.failure(ExtractionException("Extraction failed: ${e.message}", e))
        }
    }
}
