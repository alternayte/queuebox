package org.nxtspec

private const val CLAIM_LOCK_TIMEOUT_MS = 10000

/**
 * Runs [block] while this session holds an exclusive application lock on [resource], so one
 * claim on the table runs at a time across every replica.
 *
 * The lock is owned by the session, not by the transaction. Exposed can run a repository with
 * JDBC auto-commit enabled, so a transaction-owned application lock is not available reliably on
 * every connection. The row locks of the claim stay until the transaction commits.
 */
internal fun <T> withClaimLock(conn: java.sql.Connection, resource: String, block: () -> T): T {
    val sql = """
        DECLARE @result int;
        EXEC @result = sp_getapplock
            @Resource = ?,
            @LockMode = 'Exclusive',
            @LockOwner = 'Session',
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
    try {
        return block()
    } finally {
        conn.prepareStatement("EXEC sp_releaseapplock @Resource=?, @LockOwner='Session';").use { stmt ->
            stmt.setString(1, resource)
            stmt.execute()
        }
    }
}
