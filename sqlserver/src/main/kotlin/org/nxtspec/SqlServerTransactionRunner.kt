package org.nxtspec

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.nxtspec.repository.TransactionRunner

/**
 * Runs a block inside one Exposed transaction on SQL Server.
 *
 * A repository call inside the block joins this transaction, because Exposed keeps the
 * transaction in the coroutine context. See F-002.
 */
class SqlServerTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = newSuspendedTransaction { block() }
}
