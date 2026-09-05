package org.nxtspec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Failed renewal cancels work; the SQL fence still protects against non-cancellable I/O. */
suspend fun <T> withClaimLease(leaseMs: Long, renew: suspend () -> Boolean, work: suspend () -> T): T = coroutineScope {
    val owner = this
    val renewal = launch {
        while (true) {
            delay((leaseMs / RENEWAL_FRACTION).coerceAtLeast(1))
            val owned = try {
                renew()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!owned) {
                owner.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                    CancellationException("Claim ownership lost")
                )
                break
            }
        }
    }
    try {
        work()
    } finally {
        renewal.cancelAndJoin()
    }
}

private const val RENEWAL_FRACTION = 3
