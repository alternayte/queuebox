package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import org.nxtspec.transform.InboxTransformContext
import org.nxtspec.transform.InboxTransformPipeline
import org.nxtspec.transform.InboxTransformResult
import java.util.UUID

sealed class InboxHandlerResult {
    data class Accepted(val messageId: UUID) : InboxHandlerResult()
    data object Duplicate : InboxHandlerResult()
    data class ExtractionFailed(val reason: String) : InboxHandlerResult()
    data class StorageFailed(val reason: String) : InboxHandlerResult()
    data class TransformFailed(val reason: String) : InboxHandlerResult()
}

class InboxHandler(
    private val repository: InboxRepositoryInterface,
    private val extractor: IdempotencyExtractor,
    private val metricsCollector: MetricsCollectorInterface? = null,
    private val transformPipeline: InboxTransformPipeline? = null
) {
    suspend fun handle(source: String, sourceConfig: SourceConfig.Http, payload: JsonElement): InboxHandlerResult {
        val messageId = UUID.randomUUID()

        // Extract idempotency key BEFORE transform (from original payload)
        val idempotencyKey = extractor.extract(payload, sourceConfig.idempotencyKeyPath)
            .getOrElse { return InboxHandlerResult.ExtractionFailed(it.message ?: "Unknown extraction error") }

        // Extract optional aggregate ID BEFORE transform
        val aggregateId = sourceConfig.aggregateIdPath?.let {
            extractor.extract(payload, it).getOrNull()
        }

        // Extract optional event type BEFORE transform
        val eventType = sourceConfig.eventTypePath?.let {
            extractor.extract(payload, it).getOrNull()
        }

        // Apply transform if configured
        val transformedPayload = if (transformPipeline != null && sourceConfig.transform != null) {
            val context = InboxTransformContext(
                messageId = messageId,
                source = source,
                idempotencyKey = idempotencyKey,
                eventType = eventType,
                timestamp = Clock.System.now()
            )
            when (val result = transformPipeline.transform(payload, sourceConfig.transform, context)) {
                is InboxTransformResult.Success -> result.payload
                is InboxTransformResult.Rejected -> return InboxHandlerResult.TransformFailed(result.reason)
            }
        } else {
            payload
        }

        // Build inbox message with (possibly transformed) payload
        val message = InboxMessage(
            id = messageId,
            source = source,
            idempotencyKey = idempotencyKey,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = transformedPayload
        )

        // Store with deduplication
        return when (val result = repository.store(message)) {
            is InboxResult.Stored -> {
                metricsCollector?.recordInboxReceived()
                InboxHandlerResult.Accepted(message.id)
            }
            is InboxResult.Duplicate -> {
                metricsCollector?.recordInboxDuplicate()
                InboxHandlerResult.Duplicate
            }
            is InboxResult.Error -> InboxHandlerResult.StorageFailed(result.message)
        }
    }
}
