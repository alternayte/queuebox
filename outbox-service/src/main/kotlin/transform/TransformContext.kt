package org.nxtspec.transform

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Context variables that are injected into JSONata expressions during transformation.
 *
 * These variables are accessible in JSONata expressions using the $ prefix:
 * - $messageId - The unique identifier of the message being transformed
 * - $topic - The topic/event type of the message
 * - $attempt - The number of failed deliveries so far. It is 0 on the first delivery.
 * - $timestamp - ISO-8601 formatted timestamp of the message
 * - $source - Optional source identifier for the message
 *
 * @property messageId The unique identifier of the outbox message
 * @property topic The topic or event type associated with the message
 * @property attempt The number of failed deliveries so far. The column default is 0, and only
 *  a scheduled retry raises it, so the first delivery sees 0.
 * @property timestamp The timestamp when the message was created or enqueued
 * @property source Optional identifier indicating the source of the message
 */
data class TransformContext(
    val messageId: UUID,
    val topic: String,
    val attempt: Int,
    val timestamp: Instant,
    val source: String? = null
)
