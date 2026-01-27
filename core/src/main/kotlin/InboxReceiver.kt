package org.nxtspec

sealed class InboxResult {
    data object Stored : InboxResult()
    data object Duplicate : InboxResult()
    data class Error(val message: String) : InboxResult()
}

interface InboxReceiver {
    suspend fun receive(message: InboxMessage): InboxResult
}
