package org.nxtspec.app.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request payload for testing JSONata transform expressions.
 *
 * @property expression The JSONata expression to evaluate
 * @property payload The JSON payload to transform
 * @property mockTopic Optional topic to use in transform context (defaults to "test.topic")
 * @property mockSource Optional source identifier for transform context
 * @property timeoutMs Optional timeout in milliseconds (defaults to 100)
 */
@Serializable
data class TransformTestRequest(
    val expression: String,
    val payload: JsonElement,
    val mockTopic: String? = null,
    val mockSource: String? = null,
    val timeoutMs: Long? = null
)

/**
 * Response from the transform testing endpoint.
 *
 * @property success Whether the transform evaluation succeeded
 * @property result The transformed output (null on failure)
 * @property error Error message (null on success)
 * @property context The transform context used for evaluation (null on failure)
 */
@Serializable
data class TransformTestResponse(
    val success: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
    val context: TransformContextDto? = null
)

/**
 * DTO representation of the transform context for API responses.
 *
 * @property messageId The UUID used for the message in this test
 * @property topic The topic used for the transform context
 * @property attempt The attempt number (always 1 for tests)
 * @property timestamp ISO-8601 formatted timestamp
 */
@Serializable
data class TransformContextDto(
    val messageId: String,
    val topic: String,
    val attempt: Int,
    val timestamp: String
)
