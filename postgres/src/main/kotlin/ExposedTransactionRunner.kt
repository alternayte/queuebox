package org.nxtspec

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.nxtspec.repository.TransactionRunner

/**
 * Runs a block inside one Exposed transaction. See F-002.
 *
 * Exposed does not join an open transaction on its own. `newSuspendedTransaction` always starts
 * a new transaction. Every repository method therefore calls `joinOrNewTransaction`, which joins
 * the transaction that this runner opened. The outbox insert and the inbox mark of the relay
 * commit together, or neither commits.
 */
class ExposedTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = newSuspendedTransaction { block() }
}
