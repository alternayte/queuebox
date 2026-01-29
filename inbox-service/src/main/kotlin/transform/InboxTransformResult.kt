package org.nxtspec.transform

import kotlinx.serialization.json.JsonElement

/**
 * Result of an inbox transform operation.
 *
 * Unlike outbox transforms which have retry semantics, inbox transforms are simpler:
 * - [Success]: Transform succeeded, contains the transformed payload to store
 * - [Rejected]: Transform failed and message should not be stored
 *
 * This simplified model is appropriate for inbox because:
 * 1. Inbox messages come from external sources (HTTP webhooks, RabbitMQ)
 * 2. Retries are handled by the source (HTTP caller can retry, RabbitMQ can NACK)
 * 3. We either accept and store the message, or reject it
 */
sealed class InboxTransformResult {
    /**
     * Transform completed successfully.
     *
     * @property payload The transformed JSON payload to store in the inbox
     */
    data class Success(val payload: JsonElement) : InboxTransformResult()

    /**
     * Transform failed and message should be rejected (not stored).
     *
     * For HTTP sources, this results in a 422 Unprocessable Entity response.
     * For RabbitMQ sources, this results in a NACK without requeue.
     *
     * @property reason Explanation of why the message was rejected
     */
    data class Rejected(val reason: String) : InboxTransformResult()
}
