package org.nxtspec.repository

/**
 * Runs a block of repository calls inside one database transaction.
 *
 * The inbox relay needs the outbox insert and the inbox mark to commit together. See F-002.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
