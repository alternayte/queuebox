package org.nxtspec

import kotlinx.serialization.Serializable

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

    companion object {
        fun canTransitionTo(from: MessageState, to: MessageState): Boolean {
            return when (from) {
                is Pending -> to is Processing
                is Processing -> to is Sent || to is Failed
                is Failed -> to is Processing || to is Dead
                is Sent -> false
                is Dead -> false
            }
        }
    }
}
