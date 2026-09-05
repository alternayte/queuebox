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
    val processedAt: Instant? = null,
    /** Identifier that follows the message across the system. See F-047. */
    val correlationId: String? = null,
    /**
     * The moment of the claim that this copy of the message belongs to. It is the fence token
     * of the seventh review gate. A terminal write carries the token back, so a worker that
     * lost the claim cannot overwrite the row of the new owner. The value is null for a row
     * that no worker holds.
     */
    val claimedAt: Instant? = null
)
