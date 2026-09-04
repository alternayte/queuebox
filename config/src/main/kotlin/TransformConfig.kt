package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for JSONata-based payload transformation.
 *
 * @property expression The JSONata expression to apply to the payload
 * @property timeoutMs Maximum execution time in milliseconds
 * @property maxDepth Maximum recursion depth for the expression
 * @property onError Strategy for handling transformation errors
 */
@Serializable
data class TransformConfig(
    val expression: String,
    val timeoutMs: Long = 100,
    val maxDepth: Int = 100,
    val onError: TransformErrorStrategy = TransformErrorStrategy.Fail
)

/**
 * Strategy for handling transformation errors.
 *
 * Write the value in YAML exactly as the constant is spelled: `Fail`, `Skip` or `Dead`. Hoplite
 * matches an enum constant by its exact name, so `onError: fail` does not decode. The
 * `@SerialName` annotations below serve kotlinx.serialization, not the configuration loader.
 * The @SerialName annotations are for kotlinx.serialization compatibility.
 */
@Serializable
enum class TransformErrorStrategy {
    /** Fail message processing, message goes to retry queue */
    @SerialName("fail")
    Fail,

    /** Skip transform, use original payload */
    @SerialName("skip")
    Skip,

    /** Mark as dead letter immediately */
    @SerialName("dead")
    Dead
}
