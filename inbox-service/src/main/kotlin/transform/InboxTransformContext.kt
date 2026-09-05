package org.nxtspec.transform

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Context variables that are injected into inbox transform expressions.
 *
 * These variables are accessible in JSONata expressions using the $ prefix:
 * - $messageId - A unique identifier for this inbox message
 * - $source - The source name from configuration (e.g., "stripe-webhooks")
 * - $idempotencyKey - The extracted idempotency key (may be null if extraction failed)
 * - $eventType - The extracted event type (may be null if not configured)
 * - $timestamp - ISO-8601 formatted timestamp of when the message was received
 *
 * @property messageId A unique identifier generated for this inbox message
 * @property source The configured source name
 * @property idempotencyKey The idempotency key extracted from the original payload
 * @property eventType The event type extracted from the original payload (if configured)
 * @property timestamp The timestamp when the message was received
 */
data class InboxTransformContext(
    val messageId: UUID,
    val source: String,
    val idempotencyKey: String?,
    val eventType: String?,
    val timestamp: Instant
) {
    /**
     * Converts to the outbox [TransformContext] for reusing [TransformEngine].
     *
     * Maps inbox fields to outbox context variables:
     * - messageId -> messageId
     * - eventType (or empty) -> topic
     * - 0 -> attempt (always 0 for a source transform, which runs once)
     * - timestamp -> timestamp
     * - source -> source
     */
    fun toTransformContext(): TransformContext = TransformContext(
        messageId = messageId,
        topic = eventType ?: "",
        attempt = 0,
        timestamp = timestamp,
        source = source
    )
}
