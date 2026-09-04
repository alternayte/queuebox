package org.nxtspec.transform

import kotlinx.serialization.json.JsonElement
import org.nxtspec.TransformConfig
import org.nxtspec.TransformErrorStrategy
import org.nxtspec.metrics.MetricsCollectorInterface

/**
 * Pipeline that applies route-level and destination-level transforms in sequence.
 *
 * The pipeline processes transforms in order:
 * 1. Route-level transform (if configured)
 * 2. Destination-level transform (if configured)
 *
 * Each transform uses the output of the previous stage as input.
 * Error handling is configurable per transform via [TransformErrorStrategy].
 *
 * @property engine The JSONata transform engine to use for evaluation
 * @property metricsCollector Optional collector that counts a failure by error strategy (F-052)
 */
class TransformPipeline(
    private val engine: TransformEngine,
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    /**
     * Applies route and destination transforms in sequence to a message payload.
     *
     * @param payload The original JSON payload to transform
     * @param routeTransform Optional route-level transform configuration
     * @param destinationTransform Optional destination-level transform configuration
     * @param context Context variables available to the transform expressions
     * @return [TransformResult] indicating success, error (retry), or dead-letter
     */
    suspend fun transform(
        payload: JsonElement,
        routeTransform: TransformConfig?,
        destinationTransform: TransformConfig?,
        context: TransformContext
    ): TransformResult {
        var currentPayload = payload

        // 1. Apply route-level transform (if configured)
        routeTransform?.let { config ->
            when (val result = applyTransform(currentPayload, config, context)) {
                is TransformResult.Success -> currentPayload = result.payload
                is TransformResult.Error -> {
                    return handleError(result, config.onError, payload)
                }
                is TransformResult.DeadLetter -> return result
            }
        }

        // 2. Apply destination-level transform (if configured)
        destinationTransform?.let { config ->
            when (val result = applyTransform(currentPayload, config, context)) {
                is TransformResult.Success -> currentPayload = result.payload
                is TransformResult.Error -> {
                    // For destination transform, fallback is post-route payload
                    return handleError(result, config.onError, currentPayload)
                }
                is TransformResult.DeadLetter -> return result
            }
        }

        return TransformResult.Success(currentPayload)
    }

    /**
     * Applies a single transform to the payload.
     */
    private fun applyTransform(
        payload: JsonElement,
        config: TransformConfig,
        context: TransformContext
    ): TransformResult {
        return engine.evaluate(
            expression = config.expression,
            payload = payload,
            context = context,
            timeoutMs = config.timeoutMs,
            maxDepth = config.maxDepth
        ).fold(
            onSuccess = { TransformResult.Success(it) },
            onFailure = { TransformResult.Error(it.message ?: "Unknown transform error") }
        )
    }

    /**
     * Handles a transform error according to the configured error strategy.
     *
     * @param error The error that occurred during transformation
     * @param strategy The error handling strategy to apply
     * @param fallbackPayload The payload to use if strategy is SKIP
     */
    private fun handleError(
        error: TransformResult.Error,
        strategy: TransformErrorStrategy,
        fallbackPayload: JsonElement
    ): TransformResult {
        // F-052: the strategy name is a fixed enumeration, so the label set stays bounded.
        metricsCollector?.recordTransformFailure(strategyLabel(strategy))
        return applyStrategy(strategy, error, fallbackPayload)
    }

    private fun strategyLabel(strategy: TransformErrorStrategy): String = when (strategy) {
        TransformErrorStrategy.Skip -> "skip"
        TransformErrorStrategy.Fail -> "fail"
        TransformErrorStrategy.Dead -> "dead"
    }

    private fun applyStrategy(
        strategy: TransformErrorStrategy,
        error: TransformResult.Error,
        fallbackPayload: JsonElement
    ): TransformResult = when (strategy) {
        TransformErrorStrategy.Skip -> TransformResult.Success(fallbackPayload)
        TransformErrorStrategy.Fail -> error
        TransformErrorStrategy.Dead -> TransformResult.DeadLetter(error.message)
    }
}
