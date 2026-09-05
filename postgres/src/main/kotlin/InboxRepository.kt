package org.nxtspec

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.nxtspec.repository.InboxRepositoryInterface
import java.util.UUID
import kotlin.time.Duration

class InboxRepository(
    private val columnMapping: InboxColumnMapping = InboxColumnMapping(),
    private val tableName: String = "inbox"
) : InboxRepositoryInterface {
    private val table = DynamicInboxTable(columnMapping, tableName)

    // One lock key per inbox table, so two different inbox tables do not block each other.
    // String.hashCode is specified by the language, so the key is stable across processes.
    private val claimLockKey: Long = tableName.hashCode().toLong()

    // Every identifier that a raw SQL string interpolates passes through this function.
    // ConfigValidator rejects an identifier that is not a plain SQL identifier. Quoting is
    // the second defence. See F-011.
    private fun q(identifier: String): String {
        require(!identifier.contains('"')) { "Invalid SQL identifier: '$identifier'" }
        return "\"$identifier\""
    }
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
            val inserted = table.insertIgnore {
                it[id] = message.id
                it[messageSrc] = message.source
                it[idempotencyKey] = message.idempotencyKey
                it[aggregateId] = message.aggregateId
                it[eventType] = message.eventType
                it[payload] = message.payload
                it[state] = initialState
                it[createdAt] = now
                it[correlationId] = message.correlationId
            }

            if (inserted.insertedCount == 0) {
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

    /**
     * F-001 and F-006: claims pending inbox messages in one statement against the base table.
     *
     * The previous query applied FOR UPDATE SKIP LOCKED to the output of a common table
     * expression, so PostgreSQL took no row locks and two replicas claimed the same rows.
     * The claim is now a single UPDATE driven by a locking SELECT over the base table.
     *
     * Choice recorded for the one message per aggregate rule: the claim takes one transaction
     * advisory lock, so only one claim runs at a time against this inbox table. The rule itself
     * is applied in two parts. The SQL excludes an aggregate that already has a committed row
     * in state 'processing'. The Kotlin step then keeps the oldest message per aggregate inside
     * the claimed batch and releases the rest to state 'pending' in the same transaction.
     *
     * The advisory lock is necessary. Without it a second replica cannot see the uncommitted
     * claim of the first replica, so both replicas claim a different message of one aggregate.
     *
     * Resulting guarantee: at most one message per aggregate identifier is in state
     * 'processing' at any time, across every replica. The claim itself does not run in
     * parallel, which is the cost of the guarantee. The claim is one short statement.
     */
    override suspend fun claimPending(batchSize: Int): List<InboxMessage> = joinOrNewTransaction {
        val t = q(tableName)
        val conn0 = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .connection.connection as java.sql.Connection

        // Serialise the claim against every other replica. The lock is released on commit.
        conn0.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { stmt ->
            stmt.setLong(1, claimLockKey)
            stmt.executeQuery().use { it.next() }
        }

        val idCol = q(columnMapping.id)
        val stateCol = q(columnMapping.state)
        val aggregateCol = q(columnMapping.aggregateId)
        val createdAtCol = q(columnMapping.createdAt)

        val sql = """
            UPDATE $t AS target
            SET $stateCol = 'processing',
                ${q(columnMapping.claimedAt)} = ?
            FROM (
                SELECT $idCol AS claim_id
                FROM $t
                WHERE $stateCol = 'pending'
                  AND ( $aggregateCol IS NULL
                        OR $aggregateCol NOT IN (
                            SELECT DISTINCT $aggregateCol FROM $t
                            WHERE $aggregateCol IS NOT NULL AND $stateCol = 'processing'
                        ) )
                ORDER BY $createdAtCol ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            ) AS candidates
            WHERE target.$idCol = candidates.claim_id
            RETURNING target.$idCol, target.${q(columnMapping.source)},
                      target.${q(columnMapping.idempotencyKey)}, target.$aggregateCol,
                      target.${q(columnMapping.eventType)}, target.${q(columnMapping.payload)},
                      target.$stateCol, target.$createdAtCol, target.${q(columnMapping.processedAt)},
                      target.${q(columnMapping.correlationId)}, target.${q(columnMapping.claimedAt)}
        """.trimIndent()

        val now = Clock.System.now()
        val nowTimestamp = java.sql.Timestamp.from(
            java.time.Instant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong())
        )

        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .connection.connection as java.sql.Connection
        val claimed = conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, nowTimestamp)
            stmt.setInt(2, batchSize)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<InboxMessage>()
                while (rs.next()) {
                    results.add(rs.toInboxMessageFromResultSet())
                }
                results
            }
        }

        val (kept, released) = applyAggregateRule(claimed)

        if (released.isNotEmpty()) {
            table.update({ table.id inList released.map { it.id } }) {
                it[state] = "pending"
                it[claimedAt] = null
            }
        }

        kept
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

    /**
     * Seventh review gate: the write is fenced on the state and on the claim token, so a
     * worker that lost the claim cannot overwrite the row of the new owner.
     */
    override suspend fun markProcessed(id: UUID, claimedAt: Instant?): Boolean = joinOrNewTransaction {
        val now = Clock.System.now()
        table.update({ claimFence(id, claimedAt) }) {
            it[state] = "processed"
            it[processedAt] = now
        } > 0
    }

    override suspend fun markDead(id: UUID, claimedAt: Instant?): Boolean = joinOrNewTransaction {
        table.update({ claimFence(id, claimedAt) }) {
            it[state] = "dead"
            it[this.claimedAt] = null
        } > 0
    }

    /**
     * Seventh review gate: the predicate of every terminal write.
     *
     * The row must still be in state 'processing', and it must still carry the claim that the
     * caller holds. A null token matches any claim, which serves an operator tool that holds
     * no claim of its own.
     */
    private fun claimFence(id: UUID, claimedAt: Instant?): org.jetbrains.exposed.sql.Op<Boolean> {
        val base = (table.id eq id) and (table.state eq "processing")
        return if (claimedAt == null) base else base and (table.claimedAt eq claimedAt)
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

    private fun java.sql.ResultSet.toInboxMessageFromResultSet(): InboxMessage = InboxMessage(
        id = java.util.UUID.fromString(getString(columnMapping.id)),
        source = getString(columnMapping.source),
        idempotencyKey = getString(columnMapping.idempotencyKey),
        aggregateId = getString(columnMapping.aggregateId),
        eventType = getString(columnMapping.eventType),
        payload = kotlinx.serialization.json.Json.parseToJsonElement(getString(columnMapping.payload)),
        state = stringToMessageState(getString(columnMapping.state)),
        createdAt = getTimestamp(columnMapping.createdAt).toInstant().let {
            kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
        },
        processedAt = getTimestamp(columnMapping.processedAt)?.toInstant()?.let {
            kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
        },
        correlationId = getString(columnMapping.correlationId),
        claimedAt = getTimestamp(columnMapping.claimedAt)?.toInstant()?.let {
            kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
        }
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
}
