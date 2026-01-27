package org.nxtspec

import kotlinx.serialization.json.JsonElement
import java.util.UUID

sealed class InboxHandlerResult {
    data class Accepted(val messageId: UUID) : InboxHandlerResult()
    data object Duplicate : InboxHandlerResult()
    data class ExtractionFailed(val reason: String) : InboxHandlerResult()
    data class StorageFailed(val reason: String) : InboxHandlerResult()
}

class InboxHandler(
    private val repository: InboxRepository,
    private val extractor: IdempotencyExtractor
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
            is InboxResult.Stored -> InboxHandlerResult.Accepted(message.id)
            is InboxResult.Duplicate -> InboxHandlerResult.Duplicate
            is InboxResult.Error -> InboxHandlerResult.StorageFailed(result.message)
        }
    }
}
