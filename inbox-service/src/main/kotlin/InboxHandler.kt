package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import org.nxtspec.logging.logger
import org.nxtspec.metrics.InboxRejectionReason
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
    private val log = logger<InboxHandler>()

    suspend fun handle(
        source: String,
        sourceConfig: SourceConfig.Http,
        payload: JsonElement,
        /** The identifier that follows the message across the system. See F-047. */
        correlationId: String? = null
    ): InboxHandlerResult {
        val messageId = UUID.randomUUID()

        // Extract every path BEFORE transform, from the original payload, with one parse. See F-025.
        val paths = buildMap {
            put(IDEMPOTENCY_KEY, sourceConfig.idempotencyKeyPath)
            sourceConfig.aggregateIdPath?.let { put(AGGREGATE_ID_KEY, it) }
            sourceConfig.eventTypePath?.let { put(EVENT_TYPE_KEY, it) }
        }
        val extracted = extractor.extractAll(payload, paths)

        val idempotencyKey = extracted[IDEMPOTENCY_KEY]
        if (idempotencyKey == null) {
            return rejectMissingKey(source, sourceConfig.idempotencyKeyPath)
        }
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
                is InboxTransformResult.Rejected -> {
                    metricsCollector?.recordInboxRejection(InboxRejectionReason.TRANSFORM_FAILED)
                    return InboxHandlerResult.TransformFailed(result.reason)
                }
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
            payload = transformedPayload,
            correlationId = correlationId,
            consumption = sourceConfig.consumption
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
            is InboxResult.Error -> {
                metricsCollector?.recordInboxRejection(InboxRejectionReason.STORAGE_FAILED)
                InboxHandlerResult.StorageFailed(result.message)
            }
        }
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "idempotencyKey"
        const val AGGREGATE_ID_KEY = "aggregateId"
        const val EVENT_TYPE_KEY = "eventType"
    }

    /**
     * Rejects a message whose idempotency key path matched nothing.
     *
     * F-052: the reason is a fixed enumeration, never the path or the payload. The reason reaches
     * the 400 response body, and the caller of an inbox source is an untrusted webhook sender. The
     * configured JSONPath is internal configuration, so it must not travel back. The operator
     * needs it, so the log line carries it instead.
     */
    private fun rejectMissingKey(source: String, path: String): InboxHandlerResult {
        metricsCollector?.recordInboxRejection(InboxRejectionReason.EXTRACTION_FAILED)
        log.warn(
            "The idempotency key path '{}' of source '{}' matched nothing. The message is " +
                "rejected with 400.",
            path,
            source
        )
        return InboxHandlerResult.ExtractionFailed(
            "The request body does not carry the idempotency key that this source needs."
        )
    }
}
