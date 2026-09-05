package org.nxtspec

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** A hint only: retained SQL rows remain the source of delivery truth. */
class DeliverySignal {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    fun wake() {
        channel.trySend(Unit)
    }
    suspend fun await(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { channel.receive() }
    }
}
