package org.nxtspec.transform

import kotlinx.serialization.json.JsonElement

/**
 * Result of a transform operation.
 *
 * This sealed class represents the three possible outcomes of transforming a message payload:
 * - [Success]: Transform succeeded, contains the transformed payload
 * - [Error]: Transform failed, message should be retried
 * - [DeadLetter]: Transform failed fatally, message should be dead-lettered immediately
 */
sealed class TransformResult {
    /**
     * Transform completed successfully.
     *
     * @property payload The transformed JSON payload to use for publishing
     */
    data class Success(val payload: JsonElement) : TransformResult()

    /**
     * Transform failed with a recoverable error.
     *
     * The message should be scheduled for retry according to the retry strategy.
     *
     * @property message Description of the error that occurred
     */
    data class Error(val message: String) : TransformResult()

    /**
     * Transform failed fatally or was configured to dead-letter on error.
     *
     * The message should be marked as dead immediately without retry.
     *
     * @property reason Explanation of why the message was dead-lettered
     */
    data class DeadLetter(val reason: String) : TransformResult()
}
