package org.nxtspec.transform

import com.dashjoin.jsonata.Functions
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.LinkedHashMap
import com.dashjoin.jsonata.json.Json as JsonataJson

/**
 * JSONata expression evaluation engine with caching and timeout protection.
 *
 * This engine compiles and caches JSONata expressions, evaluates them against JSON payloads
 * with injected context variables, and enforces execution timeouts to prevent runaway expressions.
 *
 * @property maxCacheSize Maximum number of compiled expressions to cache (default: 1000)
 */
class TransformEngine(private val maxCacheSize: Int = 1000) {
    private val expressionCache: MutableMap<String, Jsonata> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Jsonata>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Jsonata>?): Boolean =
                size > maxCacheSize
        }
    )

    /**
     * Evaluates a JSONata expression against a JSON payload with context variable injection.
     *
     * Context variables are accessible in the expression using the $ prefix:
     * - $messageId, $topic, $attempt, $timestamp, $source
     *
     * @param expression The JSONata expression to evaluate
     * @param payload The JSON payload to transform
     * @param context Context variables to inject into the expression
     * @param timeoutMs Maximum execution time in milliseconds (default: 100)
     * @param maxDepth Maximum recursion depth for expression evaluation (default: 100)
     * @return Result containing the transformed JsonElement or an error
     */
    fun evaluate(
        expression: String,
        payload: JsonElement,
        context: TransformContext,
        timeoutMs: Long = 100,
        maxDepth: Int = 100
    ): Result<JsonElement> = runCatching {
        val jsonata = getOrCompile(expression)

        // Parse the input JSON to a Map/List structure for JSONata
        val inputData = JsonataJson.parseJson(payload.toString())

        // Create a frame and bind context variables
        val frame = jsonata.createFrame()
        bindContextVariables(frame, context)

        // Set timeout and recursion depth limits
        frame.setRuntimeBounds(timeoutMs, maxDepth)

        // Evaluate the expression
        val result = jsonata.evaluate(inputData, frame)

        // Convert result back to JsonElement
        parseResult(result)
    }

    /**
     * Validates a JSONata expression by attempting to compile it.
     *
     * This should be called at startup to fail fast on invalid expressions.
     *
     * @param expression The JSONata expression to validate
     * @return Result.success if valid, Result.failure with the parse error if invalid
     */
    fun validateExpression(expression: String): Result<Unit> = runCatching {
        Jsonata.jsonata(expression)
    }

    /**
     * Gets a compiled expression from cache or compiles and caches it.
     *
     * The cache is a bounded, access-order LinkedHashMap. Its removeEldestEntry
     * callback evicts the least recently used entry when the size exceeds
     * maxCacheSize. The map itself is synchronized, so no code modifies it from
     * inside a mapping function.
     */
    private fun getOrCompile(expression: String): Jsonata {
        synchronized(expressionCache) {
            val cached = expressionCache[expression]
            if (cached != null) return cached
            // JSONata compilation is not guaranteed thread-safe; serialize cache misses as well
            // as insertion, while keeping evaluation outside this critical section.
            return Jsonata.jsonata(expression).also { expressionCache[expression] = it }
        }
    }

    /**
     * Binds context variables to a JSONata frame.
     */
    private fun bindContextVariables(frame: Jsonata.Frame, context: TransformContext) {
        frame.bind("messageId", context.messageId.toString())
        frame.bind("topic", context.topic)
        frame.bind("attempt", context.attempt)
        frame.bind("timestamp", context.timestamp.toString())
        context.source?.let { frame.bind("source", it) }
    }

    /**
     * Converts the JSONata evaluation result to a JsonElement.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseResult(result: Any?): JsonElement = when (result) {
        null -> JsonNull
        is String -> JsonPrimitive(result)
        is Number -> JsonPrimitive(result)
        is Boolean -> JsonPrimitive(result)
        is Map<*, *> -> {
            val map = result as Map<String, Any?>
            JsonObject(map.mapValues { (_, v) -> parseResult(v) })
        }
        is List<*> -> {
            JsonArray(result.map { parseResult(it) })
        }
        else -> {
            // Fallback: use Functions.string for unknown types
            val jsonString = Functions.string(result, false) ?: "null"
            Json.parseToJsonElement(jsonString)
        }
    }

    /**
     * Clears the expression cache. Useful for testing or memory management.
     */
    fun clearCache() {
        expressionCache.clear()
    }

    /**
     * Returns the current number of cached expressions.
     */
    fun cacheSize(): Int = expressionCache.size
}

/**
 * Exception thrown when a transform expression exceeds the configured timeout.
 */
class TransformTimeoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
