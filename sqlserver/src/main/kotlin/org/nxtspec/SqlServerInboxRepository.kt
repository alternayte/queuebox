package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.InboxRepositoryInterface
import java.sql.Timestamp
import java.util.UUID
import kotlin.time.Duration

/**
 * SQL Server implementation of the inbox repository.
 * Uses MERGE statement for atomic insert-if-not-exists deduplication,
 * which is the SQL Server equivalent of PostgreSQL's INSERT ON CONFLICT IGNORE.
 */
class SqlServerInboxRepository(
    private val columnMapping: InboxColumnMapping = InboxColumnMapping(),
    private val tableName: String = "inbox"
) : InboxRepositoryInterface {
    private val table = SqlServerDynamicInboxTable(columnMapping, tableName)

    override suspend fun store(message: InboxMessage): InboxResult = insert(message, "pending")

    override suspend fun storeDead(message: InboxMessage): InboxResult = insert(message, "dead")

    private suspend fun insert(message: InboxMessage, initialState: String): InboxResult = joinOrNewTransaction {
        try {
            val now = Clock.System.now()
            val nowTimestamp = Timestamp.from(
                java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
            )

            // Escape column names that are SQL Server reserved words
            val sourceCol = quoteSqlServerIdentifier(columnMapping.source)
            val idempotencyKeyCol = quoteSqlServerIdentifier(columnMapping.idempotencyKey)
            val idCol = quoteSqlServerIdentifier(columnMapping.id)
            val aggregateIdCol = quoteSqlServerIdentifier(columnMapping.aggregateId)
            val eventTypeCol = quoteSqlServerIdentifier(columnMapping.eventType)
            val payloadCol = quoteSqlServerIdentifier(columnMapping.payload)
            val stateCol = quoteSqlServerIdentifier(columnMapping.state)
            val createdAtCol = quoteSqlServerIdentifier(columnMapping.createdAt)
            val correlationIdCol = quoteSqlServerIdentifier(columnMapping.correlationId)

            // Use MERGE for atomic insert-if-not-exists
            // This is the SQL Server equivalent of INSERT ... ON CONFLICT DO NOTHING
            // Keep the inserted column list in one value so the statement text stays unchanged.
            val insertColumns =
                "$idCol, $sourceCol, $idempotencyKeyCol, $aggregateIdCol, $eventTypeCol, " +
                    "$payloadCol, $stateCol, $createdAtCol, $correlationIdCol, ${quoteSqlServerIdentifier(
                        columnMapping.consumption
                    )}, ${quoteSqlServerIdentifier(columnMapping.scheduledAt)}"
            val sql = """
                MERGE ${quoteSqlServerIdentifier(tableName)} WITH (HOLDLOCK) AS target
                USING (SELECT ? AS source, ? AS idempotency_key) AS src
                ON target.$sourceCol = src.source AND target.$idempotencyKeyCol = src.idempotency_key
                WHEN NOT MATCHED THEN
                    INSERT ($insertColumns)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME());
            """.trimIndent()

            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            val rowsAffected = conn.prepareStatement(sql).use { stmt ->
                // The parameters are bound in the order of the statement text. An index counter
                // keeps the order correct and holds no literal position.
                var index = 0
                fun nextString(value: String?) = stmt.setString(++index, value)

                nextString(message.source)
                nextString(message.idempotencyKey)
                nextString(message.id.toString())
                nextString(message.source)
                nextString(message.idempotencyKey)
                nextString(message.aggregateId)
                nextString(message.eventType)
                nextString(message.payload.toString())
                nextString(initialState)
                stmt.setTimestamp(++index, nowTimestamp)
                nextString(message.correlationId)
                nextString(message.consumption)
                stmt.executeUpdate()
            }

            if (rowsAffected == 0) {
                InboxResult.Duplicate
            } else {
                InboxResult.Stored
            }
        } catch (e: Exception) {
            // Sixth review gate: a driver message carries the JDBC URL, and the reason reaches
            // a log line. Redact it where it is built, so every consumer is safe.
            InboxResult.Error(ErrorSanitizer.sanitize(e) ?: "Unknown error")
        }
    }

    @Suppress("LongMethod")
    override suspend fun claimPending(batchSize: Int, leaseMs: Long): List<InboxMessage> = joinOrNewTransaction {
        require(batchSize > 0 && leaseMs in 1..Int.MAX_VALUE.toLong())
        val t = quoteSqlServerIdentifier(tableName)
        val idCol = quoteSqlServerIdentifier(columnMapping.id)
        val aggregateIdCol = quoteSqlServerIdentifier(columnMapping.aggregateId)
        val stateCol = quoteSqlServerIdentifier(columnMapping.state)
        val createdAtCol = quoteSqlServerIdentifier(columnMapping.createdAt)
        val claimedAtCol = quoteSqlServerIdentifier(columnMapping.claimedAt)

        val conn = TransactionManager.current().connection.connection as java.sql.Connection

        // Hold the aggregate exclusion lock through the full claim-and-filter operation.
        // Exposed can run this repository with JDBC auto-commit enabled, so a transaction-owned
        // application lock is not available reliably on every connection.
        acquireClaimLock(conn)
        try {
            // The aggregate exclusion runs as its own statement. Inside the claim statement the
            // subquery reads the same table without the locking hints, and SQL Server then returns
            // no rows to a second concurrent claimer. The set of aggregates in state 'processing'
            // is bounded by the number of replicas and by the batch size, so the list stays small.
            val lockedAggregates = readLockedAggregates(conn, t, aggregateIdCol, stateCol)

            val exclusion = if (lockedAggregates.isEmpty()) {
                ""
            } else {
                val placeholders = lockedAggregates.joinToString(", ") { "?" }
                "AND ( $aggregateIdCol IS NULL OR $aggregateIdCol NOT IN ($placeholders) )"
            }

            val sql = """
            WITH candidates AS (
                SELECT TOP (?) ${quoteSqlServerIdentifier(
                columnMapping.claimToken
            )}, ${quoteSqlServerIdentifier(columnMapping.leaseExpiresAt)}, $idCol, $stateCol, $claimedAtCol
                FROM $t WITH (ROWLOCK, UPDLOCK, READPAST)
                WHERE $stateCol = 'pending' AND ${quoteSqlServerIdentifier(columnMapping.consumption)} = 'push'
                $exclusion
                ORDER BY $createdAtCol ASC
            )
            UPDATE candidates
            SET $stateCol = 'processing', $claimedAtCol = ?, ${quoteSqlServerIdentifier(
                columnMapping.claimToken
            )} = NEWID(), ${quoteSqlServerIdentifier(
                columnMapping.leaseExpiresAt
            )} = DATEADD(millisecond, $leaseMs, SYSUTCDATETIME())
            OUTPUT INSERTED.$idCol AS $idCol
            """.trimIndent()

            val now = Clock.System.now()
            val nowTimestamp = Timestamp.from(
                java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
            )

            val claimedIds = conn.prepareStatement(sql).use { stmt ->
                var parameterIndex = 1
                stmt.setInt(parameterIndex++, batchSize)
                lockedAggregates.forEach { stmt.setString(parameterIndex++, it) }
                stmt.setTimestamp(parameterIndex, nowTimestamp)
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<UUID>()
                    while (rs.next()) {
                        ids.add(UUID.fromString(rs.getString(columnMapping.id)))
                    }
                    ids
                }
            }

            if (claimedIds.isEmpty()) {
                emptyList()
            } else {
                val claimed = table
                    .selectAll()
                    .where { table.id inList claimedIds }
                    .map { it.toInboxMessageFromRow() }

                val (kept, released) = applyAggregateRule(claimed)

                if (released.isNotEmpty()) {
                    table.update({ table.id inList released.map { it.id } }) {
                        it[state] = "pending"
                        it[claimedAt] = null
                    }
                }

                kept
            }
        } finally {
            releaseClaimLock(conn)
        }
    }

    private fun acquireClaimLock(conn: java.sql.Connection) {
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
            stmt.setString(1, claimLockResource)
            stmt.setInt(2, CLAIM_LOCK_TIMEOUT_MS)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "sp_getapplock returned no result" }
                val result = rs.getInt("lock_result")
                check(result >= 0) {
                    "Could not take the inbox claim lock. sp_getapplock returned $result"
                }
            }
        }
    }

    private fun releaseClaimLock(conn: java.sql.Connection) {
        conn.prepareStatement("EXEC sp_releaseapplock @Resource=?, @LockOwner='Session';").use { stmt ->
            stmt.setString(1, claimLockResource)
            stmt.execute()
        }
    }

    private fun readLockedAggregates(
        conn: java.sql.Connection,
        table: String,
        aggregateIdCol: String,
        stateCol: String
    ): List<String> {
        val sql = """
            SELECT DISTINCT $aggregateIdCol
            FROM $table
            WHERE $aggregateIdCol IS NOT NULL AND $stateCol = 'processing'
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                val values = mutableListOf<String>()
                while (rs.next()) {
                    rs.getString(1)?.let { values.add(it) }
                }
                values
            }
        }
    }

    private fun applyAggregateRule(claimed: List<InboxMessage>): Pair<List<InboxMessage>, List<InboxMessage>> {
        val kept = mutableListOf<InboxMessage>()
        val released = mutableListOf<InboxMessage>()
        val seenAggregates = mutableSetOf<String>()

        claimed.sortedBy { it.createdAt }.forEach { message ->
            val aggregateId = message.aggregateId
            if (aggregateId == null || seenAggregates.add(aggregateId)) {
                kept.add(message)
            } else {
                released.add(message)
            }
        }

        return kept to released
    }

    override suspend fun markProcessed(id: UUID, claimToken: UUID?): Boolean = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({ claimFence(id, claimToken) }) {
            it[state] = "processed"
            it[processedAt] = now
        } > 0
    }

    override suspend fun markDead(id: UUID, claimToken: UUID?): Boolean = joinOrNewTransaction {
        table.update({ claimFence(id, claimToken) }) {
            it[state] = "dead"
            it[this.claimedAt] = null
        } > 0
    }

    private fun claimFence(id: UUID, claimToken: UUID?): org.jetbrains.exposed.sql.Op<Boolean> {
        val base = (table.id eq id) and (table.state eq "processing")
        return if (claimToken == null) {
            org.jetbrains.exposed.sql.Op.FALSE
        } else {
            base and (table.claimToken eq claimToken) and (table.leaseExpiresAt greater databaseNow)
        }
    }

    override suspend fun reclaimStale(olderThan: Duration): Int = joinOrNewTransaction {
        table.update({
            (table.state eq "processing") and
                ((table.leaseExpiresAt lessEq databaseNow) or table.leaseExpiresAt.isNull())
        }) {
            it[state] = "pending"
            it[claimedAt] = null
        }
    }

    private val databaseNow = object : org.jetbrains.exposed.sql.Expression<Instant>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
            queryBuilder.append("SYSUTCDATETIME()")
        }
    }

    override suspend fun renewClaim(id: UUID, claimToken: UUID?, leaseMs: Long): Boolean = joinOrNewTransaction {
        require(leaseMs > 0)
        val expires = object : org.jetbrains.exposed.sql.Expression<Instant>() {
            override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
                queryBuilder.append("DATEADD(millisecond, $leaseMs, SYSUTCDATETIME())")
            }
        }
        table.update({ claimFence(id, claimToken) }) { it[leaseExpiresAt] = expires } > 0
    }

    override suspend fun countByState(state: String): Long = joinOrNewTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int {
        require(state in setOf("sent", "processed", "dead")) { "Cannot delete active work" }
        return joinOrNewTransaction {
            val ids = table
                .select(table.id)
                .where { (table.state eq state) and (table.createdAt less cutoff) }
                .limit(limit)
                .map { it[table.id] }

            if (ids.isEmpty()) {
                0
            } else {
                table.deleteWhere { (table.id inList ids) and (table.state eq state) }
            }
        }
    }

    private fun ResultRow.toInboxMessageFromRow(): InboxMessage = InboxMessage(
        id = this[table.id].value,
        source = this[table.messageSrc],
        idempotencyKey = this[table.idempotencyKey],
        aggregateId = this[table.aggregateId],
        eventType = this[table.eventType],
        payload = Json.parseToJsonElement(this[table.payload]),
        state = stringToMessageState(this[table.state]),
        createdAt = this[table.createdAt],
        processedAt = this[table.processedAt],
        correlationId = this[table.correlationId],
        claimToken = this[table.claimToken],
        leaseExpiresAt = this[table.leaseExpiresAt],
        claimedAt = this[table.claimedAt]
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        // Seventh review gate. `storeDead` writes 'dead', so a dead inbox row really exists. The
        // mapper used to report it as `Failed("Unknown state: dead")`, which is wrong and
        // misleading for any read path that reaches one.
        "dead" -> MessageState.Dead
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }

    private val claimLockResource: String = "queuebox_inbox_claim_$tableName"

    companion object {
        private const val CLAIM_LOCK_TIMEOUT_MS = 10000
    }
}
