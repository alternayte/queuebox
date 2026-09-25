package org.nxtspec

import kotlinx.serialization.Serializable

/**
 * The state of one outbox or inbox message.
 *
 * The repositories hold the state machine. They write the state literals directly, and they map an
 * unknown literal to [Failed]. See site/src/content/docs/concepts/how-queuebox-works.mdx for the state set and the transitions.
 */
@Serializable
sealed class MessageState {
    @Serializable
    data object Pending : MessageState()

    @Serializable
    data object Processing : MessageState()

    @Serializable
    data object Sent : MessageState()

    @Serializable
    data class Failed(val error: String, val attempt: Int) : MessageState()

    @Serializable
    data object Dead : MessageState()
}
