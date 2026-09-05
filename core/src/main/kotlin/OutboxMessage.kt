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
    val updatedAt: Instant = Clock.System.now(),
    /**
     * The moment of the claim that this copy of the message belongs to. It is the fence token
     * of the seventh review gate. A terminal write carries the token back, so a worker that
     * lost the claim cannot overwrite the row of the new owner. The value is null for a row
     * that no worker holds.
     */
    val claimedAt: Instant? = null
)
