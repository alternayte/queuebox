package org.nxtspec.repository

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.nxtspec.UUIDSerializer
import java.util.UUID

/**
 * Selects the rows that a replay moves back to state 'pending'. See F-096.
 *
 * Every field is optional on its own. A filter with no field at all is refused by the route,
 * because a replay of every row is never an accident. The outbox row carries no `source` column,
 * so the field that narrows a replay to one kind of message is `topic`. The outbox row carries no
 * `destination` column either, so [destination] never reaches a repository directly: the route
 * resolves it to a concrete [topics] set through the real router first, because only the router
 * knows the first-match precedence between overlapping route patterns.
 */
@Serializable
data class ReplayFilter(
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val topic: String? = null,
    val destination: String? = null,
    val topics: List<String>? = null,
    val ids: List<
        @Serializable(with = UUIDSerializer::class)
        UUID
        >? = null
) {
    /** True when the filter selects nothing in particular. */
    fun isEmpty(): Boolean = createdAfter == null &&
        createdBefore == null &&
        topic == null &&
        destination == null &&
        topics.isNullOrEmpty() &&
        ids.isNullOrEmpty()
}

/** The response of a replay. See F-096. */
@Serializable
data class ReplayResponse(val moved: Long)
