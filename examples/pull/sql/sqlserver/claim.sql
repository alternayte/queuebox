-- The claim takes at most one message per aggregate. A row whose aggregate already holds a
-- message in state 'processing' under a live lease is not a candidate, and two rows of one
-- aggregate never leave one claim together.
--
-- The statement carries its own transaction control (BEGIN TRANSACTION and COMMIT TRANSACTION).
-- The claim must be alone in that transaction: a caller must put no other application work in
-- it, and the transaction must commit before any handler runs. A wider transaction around the
-- claim, or a handler started before the commit, breaks three things silently. First, the inner
-- COMMIT only lowers @@TRANCOUNT; the claim does not actually commit until the wider transaction
-- commits, so the claimed rows stay uncommitted for as long as that transaction stays open.
-- Second, on the lock-failure path the ROLLBACK unwinds @@TRANCOUNT to zero and destroys the
-- wider transaction before the THROW fires, not only the claim. Third, @LockOwner = 'Transaction'
-- ties the applock to the outermost transaction, so a wider transaction holds the per-source
-- applock open for as long as it stays open, serializing every other claim on that source behind
-- it instead of releasing the lock as soon as the claim itself commits.
--
-- Required isolation: READ COMMITTED. Tested explicitly with READ_COMMITTED_SNAPSHOT ON
-- (Azure SQL's default), because UPDLOCK is a physical lock unaffected by RCSI's snapshot reads.
--
-- sp_getapplock serializes claims per source, analogous to (not identical to) the whole-table
-- pg_advisory_xact_lock the existing push relay (InboxRepository.claimPending) already takes:
-- this lock is scoped to @src, not the whole table, because pull claims are already source-scoped
-- (C-3) and different sources must not block each other. Without it, two concurrent claimants on
-- one source deadlock (a textbook lock-order cycle between two independently-ordered UPDLOCK
-- scans joined into one UPDATE): 5.3 claims/sec at 2 workers against ~110/sec for one worker
-- alone, even with immediate retry-on-1205. With it: zero deadlocks, and a per-source throughput
-- CEILING near 110-140 claims/sec regardless of worker count, because only one worker per source
-- ever holds the lock at a time. Adding more workers on one source does not raise this ceiling.
--
-- sp_getapplock returns -1 on lock-request timeout and -3 on deadlock-victim WITHOUT raising an
-- error by itself. An unchecked return proceeds to claim unserialized, silently reintroducing the
-- deadlock this lock exists to prevent. The result is checked explicitly below and THROWn on a
-- negative return, so a caller sees a loud, typed failure instead of a silent unserialized claim.
-- A client library must treat this error as transient: back off and retry the whole call; it must
-- NOT retry immediately (see the round-2 finding on immediate-retry livelock) and must NOT treat
-- it as a data or logic error.
--
-- :cand_limit is a caller-computed bound on the candidate scan per branch, sized by the rule
-- cand_limit = LEAST(GREATEST(3 * :batch, 50), 500). It is not a tuning knob left to the
-- operator; the client library computes and binds it from :batch on every call.
--
-- The local variables below (@qbBatch, @qbLeaseMs, @qbCandLimit) carry names that differ from
-- the bound parameters (:batch, :lease_ms, :cand_limit) on purpose. A driver that sends a bound
-- parameter to sp_executesql passes it as an argument of that name, and a DECLARE cannot reuse
-- an argument name in the same batch: Msg 134, "The variable name ... has already been
-- declared." A client library must render :batch, :lease_ms and :cand_limit under a bound
-- parameter name distinct from @qbBatch, @qbLeaseMs and @qbCandLimit, never under those same
-- names, or the claim fails on every call.
BEGIN TRANSACTION;
DECLARE @qbBatch INT = :batch;
DECLARE @qbLeaseMs INT = :lease_ms;
DECLARE @qbCandLimit INT = :cand_limit; -- LEAST(GREATEST(3 * @qbBatch, 50), 500)
DECLARE @src VARCHAR(255) = :source;
DECLARE @lockresult INT;
EXEC @lockresult = sp_getapplock @Resource = @src, @LockMode = 'Exclusive',
    @LockOwner = 'Transaction', @LockTimeout = 10000;
IF @lockresult < 0
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51000, 'inbox pull claim: sp_getapplock did not acquire the per-source claim lock', 1;
END
;WITH candidates AS (
    SELECT TOP (@qbCandLimit) id, aggregate_id, scheduled_at, created_at
    FROM inbox WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE consumption = 'pull' AND source = @src AND state = 'pending'
      AND scheduled_at <= SYSUTCDATETIME()
    ORDER BY scheduled_at, created_at, id
    UNION ALL
    SELECT TOP (@qbCandLimit) id, aggregate_id, scheduled_at, created_at
    FROM inbox WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE consumption = 'pull' AND source = @src AND state = 'processing'
      AND lease_expires_at <= SYSUTCDATETIME()
    ORDER BY scheduled_at, created_at, id
),
ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(aggregate_id, '#' + CAST(id AS VARCHAR(36)))
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT TOP (@qbBatch) r.id, r.aggregate_id, r.scheduled_at, r.created_at
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = r.aggregate_id
              AND busy.source = @src
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > SYSUTCDATETIME()))
    ORDER BY r.scheduled_at, r.created_at, r.id
)
UPDATE target
SET state = 'processing', claim_token = NEWID(), claimed_at = SYSUTCDATETIME(),
    lease_expires_at = DATEADD(millisecond, @qbLeaseMs, SYSUTCDATETIME())
OUTPUT inserted.*
FROM inbox AS target WITH (UPDLOCK, ROWLOCK)
JOIN eligible AS e ON target.id = e.id
WHERE target.consumption = 'pull' AND target.source = @src
  AND ((target.state = 'pending' AND target.scheduled_at <= SYSUTCDATETIME())
    OR (target.state = 'processing' AND target.lease_expires_at <= SYSUTCDATETIME()))
  AND (target.aggregate_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM inbox AS busy
        WHERE busy.aggregate_id = target.aggregate_id
          AND busy.source = @src
          AND busy.consumption = 'pull'
          AND busy.state = 'processing'
          AND busy.lease_expires_at > SYSUTCDATETIME()));
COMMIT TRANSACTION;
