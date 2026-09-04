package org.nxtspec

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Runs a repository body in the open transaction, or in a new one. See F-002.
 *
 * `newSuspendedTransaction` always starts a new transaction. A repository method that calls it
 * directly therefore commits on its own, even inside `SqlServerTransactionRunner.inTransaction`.
 * The block of the runner then loses the all or nothing property.
 *
 * This function joins the open transaction if one exists. It starts a new suspended
 * transaction if none exists, so a standalone repository call keeps the earlier behaviour.
 */
internal suspend fun <T> joinOrNewTransaction(block: suspend Transaction.() -> T): T {
    val open = TransactionManager.currentOrNull()
    return if (open != null) {
        open.block()
    } else {
        newSuspendedTransaction { block() }
    }
}
