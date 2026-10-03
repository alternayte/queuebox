package org.nxtspec

private const val CLAIM_LOCK_TIMEOUT_MS = 10000

/**
 * Starts the implicit transaction of a connection in manual commit mode. The SQL Server driver
 * runs such a connection with IMPLICIT_TRANSACTIONS ON, and the transaction starts only at the
 * first statement that reads a table. sp_getapplock does not count, so a transaction-owned lock
 * taken first fails with "must be executed in the context of a user transaction". An explicit
 * BEGIN TRANSACTION would nest a second level that the driver's commit never closes.
 */
private const val OPEN_TRANSACTION = "IF @@TRANCOUNT = 0 SELECT TOP (0) 1 FROM sys.objects"

/**
 * Runs [block] while this connection holds an exclusive application lock on [resource], so one
 * claim on the table runs at a time across every replica.
 *
 * The lock lasts until the transaction of the claim commits or rolls back. A lock released
 * before the commit let a second claimer run while the row locks of the first claim were still
 * held. Its READPAST scan then skipped those rows, and every row that the first scan had read, so
 * the second claim returned nothing although pending rows were free.
 *
 * A connection in auto-commit mode has no transaction to own the lock. It takes a session lock
 * instead, and releases it when [block] returns, which is also when its statements commit.
 */
internal fun <T> withClaimLock(conn: java.sql.Connection, resource: String, block: () -> T): T {
    if (conn.autoCommit) {
        acquire(conn, resource, owner = "Session")
        try {
            return block()
        } finally {
            conn.prepareStatement("EXEC sp_releaseapplock @Resource=?, @LockOwner='Session';").use { stmt ->
                stmt.setString(1, resource)
                stmt.execute()
            }
        }
    }

    conn.prepareStatement(OPEN_TRANSACTION).use { it.execute() }
    acquire(conn, resource, owner = "Transaction")
    // The commit or the rollback of the transaction releases the lock.
    return block()
}

private fun acquire(conn: java.sql.Connection, resource: String, owner: String) {
    val sql = """
        DECLARE @result int;
        EXEC @result = sp_getapplock
            @Resource = ?,
            @LockMode = 'Exclusive',
            @LockOwner = '$owner',
            @LockTimeout = ?;
        SELECT @result AS lock_result;
    """.trimIndent()

    conn.prepareStatement(sql).use { stmt ->
        stmt.setString(1, resource)
        stmt.setInt(2, CLAIM_LOCK_TIMEOUT_MS)
        stmt.executeQuery().use { rs ->
            check(rs.next()) { "sp_getapplock returned no result" }
            val result = rs.getInt("lock_result")
            check(result >= 0) { "Could not take the claim lock '$resource'. sp_getapplock returned $result" }
        }
    }
}
