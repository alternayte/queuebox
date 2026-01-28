package org.nxtspec

import kotlinx.serialization.json.JsonElement
import org.nxtspec.metrics.MetricsCollectorInterface
import org.nxtspec.repository.InboxRepositoryInterface
import java.util.UUID

sealed class InboxHandlerResult {
    data class Accepted(val messageId: UUID) : InboxHandlerResult()
    data object Duplicate : InboxHandlerResult()
    data class ExtractionFailed(val reason: String) : InboxHandlerResult()
    data class StorageFailed(val reason: String) : InboxHandlerResult()
}

class InboxHandler(
    private val repository: InboxRepositoryInterface,
    private val extractor: IdempotencyExtractor,
    private val metricsCollector: MetricsCollectorInterface? = null
) {
    suspend fun handle(source: String, sourceConfig: SourceConfig.Http, payload: JsonElement): InboxHandlerResult {
        // Extract idempotency key
        val idempotencyKey = extractor.extract(payload, sourceConfig.idempotencyKeyPath)
            .getOrElse { return InboxHandlerResult.ExtractionFailed(it.message ?: "Unknown extraction error") }

        // Extract optional event type
        val eventType = sourceConfig.eventTypePath?.let {
            extractor.extract(payload, it).getOrNull()
        }

        // Build inbox message
        val message = InboxMessage(
            source = source,
            idempotencyKey = idempotencyKey,
            eventType = eventType,
            payload = payload
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
