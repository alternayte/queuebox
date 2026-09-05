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
    val claimedAt: Instant? = null,
    @Serializable(with = UUIDSerializer::class)
    val claimToken: UUID? = null,
    val leaseExpiresAt: Instant? = null,
    val consumption: String = "push",
    val scheduledAt: Instant = Clock.System.now(),
    val attempt: Int = 0,
    val lastError: String? = null
)
