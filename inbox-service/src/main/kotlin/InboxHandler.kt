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

        // Extract every path BEFORE transform, from the original payload, with one parse. See F-025.
        val paths = buildMap {
            put(IDEMPOTENCY_KEY, sourceConfig.idempotencyKeyPath)
            sourceConfig.aggregateIdPath?.let { put(AGGREGATE_ID_KEY, it) }
            sourceConfig.eventTypePath?.let { put(EVENT_TYPE_KEY, it) }
        }
        val extracted = extractor.extractAll(payload, paths)

        val idempotencyKey = extracted[IDEMPOTENCY_KEY]
            ?: return InboxHandlerResult.ExtractionFailed(
                "Path not found: ${sourceConfig.idempotencyKeyPath}"
            )
        val aggregateId = extracted[AGGREGATE_ID_KEY]
        val eventType = extracted[EVENT_TYPE_KEY]

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

    private companion object {
        const val IDEMPOTENCY_KEY = "idempotencyKey"
        const val AGGREGATE_ID_KEY = "aggregateId"
        const val EVENT_TYPE_KEY = "eventType"
    }
}
