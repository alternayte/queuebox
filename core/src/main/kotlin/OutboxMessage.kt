package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

@Serializable
data class OutboxMessage(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val topic: String,
    val key: String? = null,
    val payload: JsonElement,
    val headers: Map<String, String> = emptyMap(),
    val state: MessageState = MessageState.Pending,
    val attempt: Int = 0,
    val maxAttempts: Int = 5,
    val scheduledAt: Instant = Clock.System.now(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)
