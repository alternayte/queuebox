package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

@Serializable
data class InboxMessage(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val source: String,
    val idempotencyKey: String,
    val aggregateId: String? = null,
    val eventType: String? = null,
    val payload: JsonElement,
    val state: MessageState = MessageState.Pending,
    val createdAt: Instant = Clock.System.now(),
    val processedAt: Instant? = null
)
