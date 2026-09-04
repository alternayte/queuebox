package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

    /**
     * Third review gate, defect 1: stores the row already in state 'dead', in ONE transaction.
     *
     * A store in state 'pending' followed by a mark dead commits a claimable row first. The
     * relay polls in its own coroutine, so it can claim that row and forward a payload that the
     * transform rejected. One transaction closes the window.
     */
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
                    "$payloadCol, $stateCol, $createdAtCol, $correlationIdCol"
            val sql = """
                MERGE ${quoteSqlServerIdentifier(tableName)} AS target
                USING (SELECT ? AS source, ? AS idempotency_key) AS src
                ON target.$sourceCol = src.source AND target.$idempotencyKeyCol = src.idempotency_key
                WHEN NOT MATCHED THEN
                    INSERT ($insertColumns)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
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
                stmt.executeUpdate()
            }

            if (rowsAffected == 0) {
                InboxResult.Duplicate
            } else {
                InboxResult.Stored
            }
        } catch (e: Exception) {
            InboxResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * F-001 and F-006: claims pending inbox messages in one statement against the base table.
     *
     * The common table expression takes the row locks with ROWLOCK, UPDLOCK and READPAST, and
     * the UPDATE runs through the same expression, so the claim and the mark are atomic.
     *
     * Choice recorded for the one message per aggregate rule: the claim takes one application
     * lock, so only one claim runs at a time against this inbox table. The rule itself is
     * applied in two parts. The SQL excludes an aggregate that already has a committed row in
     * state 'processing'. The Kotlin step then keeps the oldest message per aggregate inside
     * the claimed batch and releases the rest to state 'pending' in the same transaction.
     *
     * The application lock is necessary. Without it a second replica cannot see the uncommitted
     * claim of the first replica, so both replicas claim a different message of one aggregate.
     * The lock owner is the session, because the driver runs the claim without an open
     * transaction count that sp_getapplock accepts. The release runs in a finally block, and
     * the connection pool resets the session as a second safety net.
     *
     * Resulting guarantee: at most one message per aggregate identifier is in state
     * 'processing' at any time, across every replica. The claim itself does not run in
     * parallel, which is the cost of the guarantee. The claim is one short statement.
     */
    override suspend fun claimPending(batchSize: Int): List<InboxMessage> = joinOrNewTransaction {
        val t = quoteSqlServerIdentifier(tableName)
        val idCol = quoteSqlServerIdentifier(columnMapping.id)
        val aggregateIdCol = quoteSqlServerIdentifier(columnMapping.aggregateId)
        val stateCol = quoteSqlServerIdentifier(columnMapping.state)
        val createdAtCol = quoteSqlServerIdentifier(columnMapping.createdAt)
        val claimedAtCol = quoteSqlServerIdentifier(columnMapping.claimedAt)

        val conn = TransactionManager.current().connection.connection as java.sql.Connection

        // Serialise the claim against every other replica. The lock is session scoped, so the
        // release runs in a finally block.
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
                SELECT TOP (?) $idCol, $stateCol, $claimedAtCol
                FROM $t WITH (ROWLOCK, UPDLOCK, READPAST)
                WHERE $stateCol = 'pending'
                $exclusion
                ORDER BY $createdAtCol ASC
            )
            UPDATE candidates
            SET $stateCol = 'processing', $claimedAtCol = ?
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

    /**
     * Takes the exclusive application lock that serialises the claim.
     *
     * @throws IllegalStateException when the lock is not granted inside the timeout
     */
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

    /**
     * Releases the application lock that serialises the claim.
     */
    private fun releaseClaimLock(conn: java.sql.Connection) {
        conn.prepareStatement("EXEC sp_releaseapplock @Resource = ?, @LockOwner = 'Session';").use { stmt ->
            stmt.setString(1, claimLockResource)
            stmt.execute()
        }
    }

    /**
     * Reads the aggregate identifiers that already have a message in state 'processing'.
     */
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

    /**
     * Keeps the oldest claimed message per aggregate identifier and returns the rest for
     * release. A message without an aggregate identifier is independent and is always kept.
     */
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

    override suspend fun markProcessed(id: UUID): Unit = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({ table.id eq id }) {
            it[state] = "processed"
            it[processedAt] = now
        }
        Unit
    }

    override suspend fun markDead(id: UUID): Unit = joinOrNewTransaction {
        table.update({ table.id eq id }) {
            it[state] = "dead"
            it[claimedAt] = null
        }
        Unit
    }

    /**
     * F-006: returns rows that stay in state 'processing' longer than the visibility timeout
     * back to state 'pending'.
     */
    override suspend fun reclaimStale(olderThan: Duration): Int = joinOrNewTransaction {
        val cutoff = Clock.System.now() - olderThan
        table.update({
            (table.state eq "processing") and
                ((table.claimedAt lessEq cutoff) or table.claimedAt.isNull())
        }) {
            it[state] = "pending"
            it[claimedAt] = null
        }
    }

    override suspend fun countByState(state: String): Long = joinOrNewTransaction {
        table
            .selectAll()
            .where { table.state eq state }
            .count()
    }

    /**
     * F-008: deletes at most `limit` rows per statement.
     */
    override suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int = joinOrNewTransaction {
        val ids = table
            .select(table.id)
            .where { (table.state eq state) and (table.createdAt less cutoff) }
            .limit(limit)
            .map { it[table.id] }

        if (ids.isEmpty()) {
            0
        } else {
            table.deleteWhere { table.id inList ids }
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
        correlationId = this[table.correlationId]
    )

    private fun stringToMessageState(state: String): MessageState = when (state) {
        "pending" -> MessageState.Pending
        "processing" -> MessageState.Processing
        "processed" -> MessageState.Sent
        else -> MessageState.Failed(error = "Unknown state: $state", attempt = 0)
    }

    private val claimLockResource: String = "queuebox_inbox_claim_$tableName"

    companion object {
        private const val CLAIM_LOCK_TIMEOUT_MS = 10000
    }
}
