package org.nxtspec.transform

import kotlinx.serialization.json.JsonElement
import org.nxtspec.TransformConfig
import org.nxtspec.TransformErrorStrategy

/**
 * Pipeline that applies source-level transforms to incoming messages before storage.
 *
 * Unlike the outbox pipeline which chains route and destination transforms,
 * inbox transforms are single-stage (one transform per source). This keeps
 * the configuration simple for ingress scenarios.
 *
 * Error handling strategies:
 * - [TransformErrorStrategy.Skip]: Use original payload if transform fails
 * - [TransformErrorStrategy.Fail]: Reject the message (HTTP 422 / RabbitMQ NACK)
 * - [TransformErrorStrategy.Dead]: Same as Fail for inbox (no dead letter queue concept)
 *
 * @property engine The JSONata transform engine to use for evaluation
 */
class InboxTransformPipeline(private val engine: TransformEngine) {
    /**
     * Applies the source transform to an incoming message payload.
     *
     * @param payload The original JSON payload received from the source
     * @param transform Optional source-level transform configuration
     * @param context Context variables available to the transform expression
     * @return [InboxTransformResult] indicating success or rejection
     */
    suspend fun transform(
        payload: JsonElement,
        transform: TransformConfig?,
        context: InboxTransformContext
    ): InboxTransformResult {
        // No transform configured - pass through original payload
        if (transform == null) {
            return InboxTransformResult.Success(payload)
        }

        return engine.evaluate(
            expression = transform.expression,
            payload = payload,
            context = context.toTransformContext(),
            timeoutMs = transform.timeoutMs,
            maxDepth = transform.maxDepth
        ).fold(
            onSuccess = { InboxTransformResult.Success(it) },
            onFailure = { error ->
                handleError(error, transform.onError, payload)
            }
        )
    }

    /**
     * Handles a transform error according to the configured error strategy.
     *
     * @param error The error that occurred during transformation
     * @param strategy The error handling strategy to apply
     * @param fallbackPayload The original payload to use if strategy is SKIP
     */
    private fun handleError(
        error: Throwable,
        strategy: TransformErrorStrategy,
        fallbackPayload: JsonElement
    ): InboxTransformResult = when (strategy) {
        TransformErrorStrategy.Skip -> InboxTransformResult.Success(fallbackPayload)
        TransformErrorStrategy.Fail -> InboxTransformResult.Rejected(error.message ?: "Transform failed")
        TransformErrorStrategy.Dead -> InboxTransformResult.Rejected(error.message ?: "Transform failed")
    }
}
