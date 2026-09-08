# Spike: the head-row claim removes the aggregate race (F-087)

Date: 2026-09-08. Scope: task 2 of phase-07-stop-the-bleeding. This spike proves, by experiment
against real PostgreSQL 16 and SQL Server 2022 containers, that a head-row claim statement removes
the aggregate race that finding F-087 describes. This document contains no production code and no
application test. It is evidence for task 3, which copies the two verified statements into
`examples/pull/sql`.

## Method

Two throwaway containers ran the tests: `postgres:16` and
`mcr.microsoft.com/mssql/server:2022-latest`. Each container held one `inbox` table, built from the
real schema in `postgres/src/main/kotlin/Tables.kt` (`InboxTable`), with the index
`idx_inbox_aggregate_state` on `(aggregate_id, state)`. All throwaway SQL lived under the session
scratchpad. Both containers were removed at the end of the spike. Neither container is present in
the repository.

## Section 1: the reproduction transcript

The naive claim statement applies `LIMIT 1` before it removes duplicate rows of one aggregate. Five
pending rows shared `aggregate_id = 'agg-1'`. Two `psql` sessions ran the naive statement
concurrently, each inside a transaction that held its claim for three seconds with `pg_sleep(3)`.

```sql
BEGIN;
WITH candidates AS (
    SELECT id FROM inbox
    WHERE consumption = 'pull' AND source = 's'
      AND state = 'pending' AND scheduled_at <= clock_timestamp()
      AND (aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = inbox.aggregate_id
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    ORDER BY scheduled_at, created_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target SET state = 'processing'
FROM candidates WHERE target.id = candidates.id
RETURNING target.id;
SELECT pg_sleep(3);
COMMIT;
```

Result, session A:

```
BEGIN
                  id
--------------------------------------
 e1ab6fa5-000b-4173-bdfe-7efa3992d852
(1 row)
UPDATE 1
COMMIT
```

Result, session B (started 0.3 seconds after A, while A still held its row):

```
BEGIN
                  id
--------------------------------------
 e01b43d8-223c-4879-a990-d556fb02d61c
(1 row)
UPDATE 1
COMMIT
```

Both sessions returned a row, and the two rows belong to the same aggregate. `SKIP LOCKED` let
session B move past the row session A already locked and take the next row of `agg-1`, because the
`LIMIT 1` ran before any per-aggregate deduplication. This is the defect. Two workers now own two
rows of one aggregate at the same time, and a downstream consumer that enforces one row per
aggregate hits a duplicate key error. The naive statement failed the test, as expected, and the
spike proceeded to the head-row statement.

A naive form was also run against SQL Server, without the `UPDLOCK`, `READPAST`, `ROWLOCK` hints, to
confirm the same race exists there under the ordinary `TOP (1)` pattern. Session A returned
`F50271CC-9044-4C31-B8AD-1BE13B5DF9FF` and session B, started 0.3 seconds later, returned
`71EF9790-ADD8-4850-8997-8414CD3602CA` — two different rows of the same aggregate, confirming the
race is not a PostgreSQL peculiarity.

## Section 2: the PostgreSQL result over the five arrangements

The verified PostgreSQL statement:

```sql
WITH ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           row_number() OVER (
               PARTITION BY COALESCE(aggregate_id, id::text)
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM inbox
    WHERE consumption = 'pull' AND source = :source
      AND ((state = 'pending' AND scheduled_at <= clock_timestamp())
        OR (state = 'processing' AND lease_expires_at <= clock_timestamp()))
      AND (aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = inbox.aggregate_id
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
),
picked AS (
    SELECT id FROM ready WHERE rn = 1
    ORDER BY scheduled_at, created_at, id
    LIMIT :batch
),
locked AS (
    SELECT i.id FROM inbox AS i
    JOIN picked AS p ON i.id = p.id
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target
SET state = 'processing', claim_token = gen_random_uuid(), claimed_at = clock_timestamp(),
    lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
FROM locked WHERE target.id = locked.id
RETURNING target.*;
```

The `locked` CTE selects `FOR UPDATE SKIP LOCKED` from the base table `inbox`, joined against
`picked`, and never from a CTE output. Finding F-001 of `hardening-doc.md` established that
`FOR UPDATE` applied to a CTE output takes no row lock in PostgreSQL, so this distinction is load
bearing, not stylistic.

**Reproduction re-run.** The same two-session arrangement from Section 1 ran again, this time
against the head-row statement, each session inside a transaction holding a three-second sleep after
its claim. Session A returned one row of `agg-1`. Session B, started 0.3 seconds later, returned
zero rows:

```
-- session A
                  id                  | aggregate_id
---------------------------------------+--------------
 06812a63-3326-48fe-a94b-41f8f674e889 | agg-1
UPDATE 1

-- session B
 id | aggregate_id
----+--------------
(0 rows)
UPDATE 0
```

Result: **pass**. Session B fell through to nothing, because the sibling rows of `agg-1` were never
its candidate. This confirms the claim in the brief: both workers compute the same head row, the
ordinary row lock serializes them, and the loser has no other row of that aggregate to fall back to.

**Arrangement 1 — two aggregates, one row each.** Seeded `agg-a` and `agg-b`, one pending row each.
One claim returned both rows:

```
                  id                  | aggregate_id
--------------------------------------+--------------
 46677edf-d4f2-4c16-ad50-c300f3da8b6b | agg-a
 978faf24-2d6e-4bb7-b26a-3226f1cec8c4 | agg-b
UPDATE 2
```

Result: **pass**.

**Arrangement 2 — head row `processing` with an EXPIRED lease.** Seeded one row of `agg-a` in state
`processing` with `lease_expires_at` one minute in the past. The claim reclaimed it:

```
                  id                  | aggregate_id
--------------------------------------+--------------
 7aee0091-7bd1-4f67-b989-cff8ab7f845d | agg-a
UPDATE 1
```

Result: **pass**.

**Arrangement 3 — head row `processing` with a LIVE lease, and a pending sibling.** Seeded one row
of `agg-a` in state `processing` with `lease_expires_at` one minute in the future, plus a pending
sibling of the same aggregate. The claim returned nothing for that aggregate:

```
 id | aggregate_id
----+--------------
(0 rows)
UPDATE 0
```

Result: **pass**. The live-lease row is excluded by the `state = 'processing' AND lease_expires_at
<= clock_timestamp()` branch, and the `NOT EXISTS` correlated subquery excludes its pending sibling
because a busy row of the same aggregate is active.

**Arrangement 4 — four rows with a NULL `aggregate_id`.** Seeded four pending rows, each with
`aggregate_id IS NULL`. One claim returned all four:

```
                  id                  | aggregate_id
--------------------------------------+--------------
 b81da8ee-2450-44bc-a5eb-6ee1a7482511 |
 578aec60-2945-4368-a5f4-b430fbf591bc |
 649a7422-d45f-4924-ac1b-e9897eb3fc14 |
 616ff25c-b0b3-4eb3-828a-e71b7ce964c7 |
UPDATE 4
```

Result: **pass**. `COALESCE(aggregate_id, id::text)` puts each null-aggregate row into a partition
of size one, so a null aggregate takes part in no ordering with any other row.

**Row-lock verification.** During arrangement 0 (the reproduction re-run), session A held its claim
open for three seconds while session B, started mid-hold, ran its own claim against the same table
concurrently. Session B returned zero rows rather than blocking or erroring, which is the observable
signature of `FOR UPDATE SKIP LOCKED` finding an existing row lock and skipping it. This confirms the
`locked` CTE takes real row locks from the base table. A relation-level check of `pg_locks` during
the hold showed only the ordinary `AccessShareLock`, `RowShareLock`, and `RowExclusiveLock` at the
table level, because PostgreSQL does not surface individual tuple locks in `pg_locks` except while a
second transaction is actively waiting on one; the concurrency result above is the reliable
evidence, not the relation-level snapshot.

**Total-order tie-break.** Two rows of one aggregate were seeded with an IDENTICAL `scheduled_at`
and an IDENTICAL `created_at`. `ORDER BY scheduled_at, created_at, id` (the verified statement)
produced the same head row across three repeated executions. A statement built from the same
partition but ordered only `scheduled_at, created_at` — the ordering the brief warns against — was
also run once with `SELECT * FROM picked` in place of the update, to show the shape of the failure
mode: with no third key, the window function receives ties for `ORDER BY`, and SQL leaves the row
order of a tie unspecified. Two separate connections computing that window are not guaranteed the
same physical scan order, so a tie can let two workers compute two different heads for one
aggregate, reintroducing exactly the defect that Section 1 reproduced. A live split-brain from that
statement could not be forced with confidence inside one single-node container in the time
available, because it depends on the planner choosing a different scan strategy per connection,
which is not something this spike can dictate; the risk is confirmed by SQL semantics rather than by
an empirical split, and `id` is a real primary key with no possible tie, so appending it closes the
gap completely. The verified statement keeps `id` as the third key and never omits it.

## Section 3: the SQL Server result over the same five arrangements

The verified SQL Server statement:

```sql
DECLARE @batch INT = :batch;
DECLARE @lease_ms INT = :lease_ms;
WITH ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(aggregate_id, CAST(id AS VARCHAR(36)))
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM inbox
    WHERE consumption = 'pull' AND source = :source
      AND ((state = 'pending' AND scheduled_at <= SYSUTCDATETIME())
        OR (state = 'processing' AND lease_expires_at <= SYSUTCDATETIME()))
      AND (aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = inbox.aggregate_id
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > SYSUTCDATETIME()))
),
picked AS (
    SELECT TOP (@batch) id FROM ready WHERE rn = 1
    ORDER BY scheduled_at, created_at, id
)
UPDATE target
SET state = 'processing', claim_token = NEWID(), claimed_at = SYSUTCDATETIME(),
    lease_expires_at = DATEADD(millisecond, @lease_ms, SYSUTCDATETIME())
OUTPUT inserted.*
FROM inbox AS target WITH (UPDLOCK, READPAST, ROWLOCK)
JOIN picked AS p ON target.id = p.id;
```

`WITH (UPDLOCK, READPAST, ROWLOCK)` sits on the base table `inbox` in the update's `FROM` clause,
matching the requirement that the lock apply to the base table and not to a CTE output.
`UPDLOCK` takes an update lock at claim time so a concurrent reader under `READ COMMITTED` cannot
read a stale value between the plan choosing the row and the update taking it. `READPAST` skips a
row already locked by another session, the SQL Server analogue of `SKIP LOCKED`. `ROWLOCK` forces
row-granularity locking so SQL Server does not escalate to a page or table lock during the update.
These are a distinct locking primitive from PostgreSQL's `FOR UPDATE SKIP LOCKED`, so no PostgreSQL
result was assumed here; every arrangement ran again against the SQL Server container.

**Reproduction re-run.** Ran with `sqlcmd`, five rows of `agg-1`, two sessions 0.3 seconds apart,
each holding its claim for three seconds with `WAITFOR DELAY '00:00:03'`. Session A returned one row
of `agg-1`. Session B returned zero rows:

```
-- session A
id                                   aggregate_id
------------------------------------ ------------
4E66F486-1330-428E-9307-CDD4A05A0B95 agg-1

-- session B
id                                   aggregate_id
------------------------------------ ------------
(0 rows affected)
```

Result: **pass**.

**Arrangement 1 — two aggregates, one row each.** One claim returned both rows:

```
id                                   aggregate_id
------------------------------------ ------------
E8E6FB0D-B382-4EB9-94BE-1F8C0295C31C agg-a
E540D814-1662-4E37-AC57-A25B6FEC766D agg-b
```

Result: **pass**.

**Arrangement 2 — head row `processing` with an EXPIRED lease.** The claim reclaimed it:

```
id                                   aggregate_id
------------------------------------ ------------
9837DDBC-4137-4332-8651-D347823E788F agg-a
```

Result: **pass**.

**Arrangement 3 — head row `processing` with a LIVE lease, and a pending sibling.** The claim
returned nothing for that aggregate: zero rows. Result: **pass**.

**Arrangement 4 — four rows with a NULL `aggregate_id`.** One claim returned all four:

```
id                                   aggregate_id
------------------------------------ ------------
42D1CB7E-5A7A-4E51-A05A-FB64123357F9 NULL
B5C6A65E-9511-4447-A37F-830615F98E04 NULL
2B492550-0C9E-4F4C-8F1D-9170886D4C1A NULL
59DB9B2D-D1AB-488E-9023-F39DB2B9587B NULL
```

Result: **pass**.

All five arrangements passed on both dialects. No arrangement failed. Nothing in this spike requires
a change to either statement.

## Section 4: the measured cost

The PostgreSQL container held 100,000 rows across 10,000 distinct aggregates (10 rows per
aggregate), seeded with `state = 'pending'` and consumption `'pull'`, after `ANALYZE inbox`.
`EXPLAIN (ANALYZE, BUFFERS)` ran the head-row statement with `:batch = 100`.

**Baseline, with only the schema's declared index (`idx_inbox_aggregate_state` on
`(aggregate_id, state)`) present alongside `idx_inbox_state_created` on `(state, created_at)`:**

The planner did **not** use `idx_inbox_aggregate_state` for the `NOT EXISTS` correlated subquery. It
used `idx_inbox_state_created` instead:

```
SubPlan 1
  ->  Index Scan using idx_inbox_state_created on inbox busy
        (cost=0.42..6.20 rows=1 width=0) (actual time=0.002..0.002 rows=0 loops=100000)
        Index Cond: ((state)::text = 'processing'::text)
        Filter: (aggregate_id = inbox.aggregate_id AND consumption = 'pull'
                 AND lease_expires_at > clock_timestamp())
        Buffers: shared hit=300000
```

Total execution time: **3594.009 ms**, planning time 4.413 ms, shared buffer hits 301,547. The
planner chose `idx_inbox_state_created` over `idx_inbox_aggregate_state` because its estimated
per-probe cost (6.20) was marginally lower than the aggregate index's (8.30), even though the chosen
index reads every `processing` row and re-filters by `aggregate_id` afterward, at 300,000 buffer
hits for the subplan alone.

Dropping `idx_inbox_state_created` and re-running the identical statement forced the planner onto
`idx_inbox_aggregate_state`, confirming that index alone can serve the correlated subquery
(cost 8.30 per probe, 200,000 buffer hits for the subplan, total execution 3253.476 ms) — but this
is not the outcome that occurs when the schema's full index set is present, and it is not markedly
faster.

**Improved: a purpose-built partial index.** With `idx_inbox_state_created` restored, an added index

```sql
CREATE INDEX idx_inbox_agg_state_lease ON inbox (aggregate_id, state, lease_expires_at)
    WHERE consumption = 'pull';
```

was picked by the planner for the same statement, with an index-only scan:

```
SubPlan 1
  ->  Index Only Scan using idx_inbox_agg_state_lease on inbox busy
        (cost=0.29..4.32 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=100000)
        Index Cond: (aggregate_id = inbox.aggregate_id AND state = 'processing')
        Filter: (lease_expires_at > clock_timestamp())
        Heap Fetches: 0
        Buffers: shared hit=199852
```

Total execution time: **2011.179 ms**, a reduction of roughly 44 percent against the baseline, with
fewer buffer hits (201,547 total against 301,547).

**Conclusion on the index.** The correlated subquery does not reliably use
`idx_inbox_aggregate_state` when the schema's existing indexes are all present; PostgreSQL's cost
model prefers `idx_inbox_state_created` for this query shape. Task 3 must add a purpose-built index:

```sql
CREATE INDEX idx_inbox_agg_state_lease ON inbox (aggregate_id, state, lease_expires_at)
    WHERE consumption = 'pull';
```

This index served the correlated subquery with an index-only scan and cut execution time by about
44 percent at 100,000 rows and 10,000 aggregates. This spike did not modify the schema in the
repository. Task 3 owns that change.

SQL Server cost measurement was out of scope for this step; the brief's Step 4 names only
PostgreSQL's `EXPLAIN (ANALYZE, BUFFERS)`.

## Result

Every arrangement of the head-row statement passed, on both PostgreSQL 16 and SQL Server 2022. The
naive statement reproduced the race on both dialects, as expected, and is not the statement to ship.
The head-row statement removes the race by construction: both workers compute the same head row per
aggregate, so the row lock serializes the pair, and the loser has no sibling row to fall back to. A
total order over `scheduled_at, created_at, id` is necessary for this guarantee, and dropping `id`
reopens the same class of defect that Section 1 demonstrated, though this spike could not force a
live split of that specific case inside one container.

One follow-up is required, not optional: task 3 must add `idx_inbox_agg_state_lease` alongside the
existing `idx_inbox_aggregate_state`, because the existing index is not what the planner picks for
this statement's correlated subquery once the schema's full index set is present.

---

## Round 1 fix: the statements above are SUPERSEDED

Code review found three critical defects in the statements printed in Sections 2 and 3 above.
**Do not copy those statements into a client library.** The corrected statements below are
the ones Task 3 must use. Full transcripts, EXPLAIN output, and the arrangements that found and
then closed each defect are in
`.superpowers/sdd/2026-09-08-phase-07-stop-the-bleeding/task-2-report.md`, section
'Fix round 1 of 5'.

**What was wrong.**

1. The locking node's only qual was an id equality. Under PostgreSQL's EvalPlanQual re-check,
   or under SQL Server's UPDLOCK sitting only on the final target, a row a worker already
   claimed and committed could be claimed a second time by a worker that had picked it before
   the first commit. Arrangement 0 could not detect this, because it holds the winning
   transaction open for three seconds. A new arrangement, in which the winner commits
   immediately and the loser is delayed into the lock stage, reproduced a genuine double claim
   on both dialects against the original statements.
2. The aggregate busy check was not scoped by source, so one aggregate_id shared by two
   sources could compute two different head rows.
3. The PostgreSQL plan carried a full sequential scan and an external disk sort at
   100,000 rows, taking 3.6 seconds; not viable for a hot polling loop.

**The fix.** Repeat the full readiness predicate — consumption, source, the
pending-or-expired-processing branch, and the aggregate busy check, itself now scoped by
source — as quals on the actual locking read (the base-table scan under FOR UPDATE in
PostgreSQL; the base-table scan under UPDLOCK, READPAST, ROWLOCK in SQL Server) and again in
the final UPDATE ... WHERE. Bound the candidate set by branch, through its own partial index,
before the window function runs, instead of ranking the whole table.

**Result.** Every one of 5 original arrangements plus 2 new ones (a fast-commit-then-delayed-
lock arrangement for the double-claim defect, and a two-sources-one-aggregate arrangement for
the scoping defect) passed on both PostgreSQL 16 and SQL Server 2022, the latter tested
explicitly with READ_COMMITTED_SNAPSHOT ON. PostgreSQL's warm-cache execution time at
100,000 rows / 10,000 aggregates fell to 2.155 ms. The honest cold-cache number, measured
after unvacuumed write churn and a container restart to discard shared_buffers, is
117.864 ms for the first claim only; every claim after that, with pages resident, returned to
the low-millisecond range. This does not meet the under-50-ms target on a cold first call, and
that gap is reported rather than hidden.

### Final statement, PostgreSQL
```sql
WITH candidates AS (
    (
        SELECT id, aggregate_id, scheduled_at, created_at
        FROM inbox
        WHERE consumption = 'pull' AND source = :source AND state = 'pending'
          AND scheduled_at <= clock_timestamp()
        ORDER BY scheduled_at, created_at, id
        LIMIT :cand_limit
    )
    UNION ALL
    (
        SELECT id, aggregate_id, scheduled_at, created_at
        FROM inbox
        WHERE consumption = 'pull' AND source = :source AND state = 'processing'
          AND lease_expires_at <= clock_timestamp()
        ORDER BY scheduled_at, created_at, id
        LIMIT :cand_limit
    )
),
ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           row_number() OVER (
               PARTITION BY COALESCE(aggregate_id, '#' || id::text)
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT r.id, r.aggregate_id, r.scheduled_at, r.created_at
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = r.aggregate_id
              AND busy.source = :source
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    ORDER BY r.scheduled_at, r.created_at, r.id
    LIMIT :batch
),
locked AS (
    SELECT i.id FROM inbox AS i
    JOIN eligible AS e ON i.id = e.id
    WHERE i.consumption = 'pull' AND i.source = :source
      AND ((i.state = 'pending' AND i.scheduled_at <= clock_timestamp())
        OR (i.state = 'processing' AND i.lease_expires_at <= clock_timestamp()))
      AND (i.aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = i.aggregate_id
              AND busy.source = :source
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target
SET state = 'processing', claim_token = gen_random_uuid(), claimed_at = clock_timestamp(),
    lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
FROM locked
WHERE target.id = locked.id
  AND target.consumption = 'pull' AND target.source = :source
  AND ((target.state = 'pending' AND target.scheduled_at <= clock_timestamp())
    OR (target.state = 'processing' AND target.lease_expires_at <= clock_timestamp()))
  AND (target.aggregate_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM inbox AS busy
        WHERE busy.aggregate_id = target.aggregate_id
          AND busy.source = :source
          AND busy.consumption = 'pull'
          AND busy.state = 'processing'
          AND busy.lease_expires_at > clock_timestamp()))
RETURNING target.*;
```

### Final statement, SQL Server
```sql
-- Required isolation: READ COMMITTED. Tested explicitly with READ_COMMITTED_SNAPSHOT ON
-- (Azure SQL's default), because UPDLOCK is a physical lock unaffected by RCSI's snapshot reads.
DECLARE @batch INT = :batch;
DECLARE @lease_ms INT = :lease_ms;
DECLARE @cand_limit INT = :cand_limit;
DECLARE @src VARCHAR(255) = :source;
WITH candidates AS (
    SELECT TOP (@cand_limit) id, aggregate_id, scheduled_at, created_at
    FROM inbox WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE consumption = 'pull' AND source = @src AND state = 'pending'
      AND scheduled_at <= SYSUTCDATETIME()
    ORDER BY scheduled_at, created_at, id
    UNION ALL
    SELECT TOP (@cand_limit) id, aggregate_id, scheduled_at, created_at
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
    SELECT TOP (@batch) r.id, r.aggregate_id, r.scheduled_at, r.created_at
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
    lease_expires_at = DATEADD(millisecond, @lease_ms, SYSUTCDATETIME())
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
```

### Indexes Task 3 must add
```sql
CREATE INDEX idx_inbox_pull_pending ON inbox (source, scheduled_at, created_at, id)
    WHERE consumption = 'pull' AND state = 'pending';
CREATE INDEX idx_inbox_pull_processing ON inbox (source, lease_expires_at)
    WHERE consumption = 'pull' AND state = 'processing';
CREATE INDEX idx_inbox_pull_busy ON inbox (source, aggregate_id, lease_expires_at)
    WHERE consumption = 'pull' AND state = 'processing';
```
This supersedes the 'idx_inbox_agg_state_lease' index named in the Result section above; that
index lacked the 'source' column and does not satisfy the corrected, source-scoped busy check.

**Note on scope, as the review required:** the push relay ('InboxRepository.claimPending') is
NOT source-scoped, and this spike does not change it. Push and pull differ on this point by
design; only the pull statement above carries the source scoping.

---

## Round 2 fix: index competition, SQL Server contention, and a test-gap explanation

Re-review confirmed all seven round-1 findings addressed, and found two new Important issues
plus one test gap in the round-1 fix itself. Full transcripts in the report's 'Fix round 2 of
5' section.

**Index competition (PostgreSQL).** The round-1 fix added two partial indexes that shared the
same predicate ('consumption = pull AND state = processing'):
'idx_inbox_pull_processing' and 'idx_inbox_pull_busy'. The planner is free to pick either for
the busy check, and picking the wrong one was the actual cause of the 117.864 ms cold number,
not cache warmth by itself. 'idx_inbox_pull_processing' is dropped; only 'idx_inbox_pull_busy'
remains, so there is nothing left to compete with. Re-measured cold with the settled index set:
**22.844 ms**, roughly 5 times faster than the flip-affected number.

**Contention and deadlocks (SQL Server).** Multi-worker measurement found that, without
serialization, concurrent SQL Server claimants on one source deadlock: throughput collapsed
from a single-worker rate to roughly 5.3 claims/sec at two workers, with 686 deadlock retries
needed to complete 20 successful claims, even with immediate retry-on-1205. Adding
'sp_getapplock' scoped to the source, before the claim statement — analogous to, but not the same
pattern as, the shipped push relay's whole-table 'pg_advisory_xact_lock' (the push lock serializes
every source together; this lock serializes only claims on one source) — removed the deadlocks
entirely. PostgreSQL needs no such change: 'FOR UPDATE SKIP LOCKED' never blocks waiting for a
lock, so this class of deadlock cannot arise there, confirmed with 4 concurrent workers producing
zero errors.

**The per-source SQL Server ceiling, and what an operator must do differently on each dialect**
(round 3). 'sp_getapplock' is exclusive per source, held for the whole claim transaction, so every
worker claiming against one source queues for that same lock. The measured single-active-claimant
rate was roughly 110-140 claims/sec at `batch = 25` against the test pool (one run each of 111.8
and 139.0 claims/sec; at this sample size, run-to-run variance between separate `sqlcmd`
connections is large enough to explain that spread, so it is reported as a range, not as evidence
that a second worker made the system faster). **This rate is a ceiling for that source, not a
floor to scale from**: two SQL Server workers on one source do not claim at twice the rate of one,
and a client library must plan capacity around it, not around adding workers. PostgreSQL is the
opposite: because SKIP LOCKED cannot form a wait-for cycle, multiple workers on one PostgreSQL
source genuinely divide the work and add throughput. Consequently: **on PostgreSQL, add more
workers on the same source to raise its throughput; on SQL Server, adding workers on the same
source raises nothing past the first one — raise throughput by adding more sources (sharding the
workload), or accept the roughly 110-140 claims/sec per-source ceiling.**

'cand_limit' is now sized by a rule rather than a fixed guess, on both dialects:
```
cand_limit = LEAST(GREATEST(3 * batch, 50), 500)
```

**Test gap.** The SQL Server outer 'UPDATE ... WHERE' repeats the readiness predicate, but no
interleaving exists, on SQL Server, in which a fellow pull claimant commits a change to a row
between this statement's own candidates read and its own final update of that row: 'UPDLOCK'
taken at the candidates read already excludes every other claimant from that row until commit.
The repeated predicate there defends against a writer outside this claim statement (a
maintenance job, a manual release), not against a fellow claimant, and this is stated plainly
rather than left untested-but-implied. On PostgreSQL the repeated predicate is genuinely
load-bearing against a fellow claimant, because an ordinary read takes no lock; the exact
statement that proves this, sleep included, is printed in the report.

### Final statement, PostgreSQL (unchanged since round 1)
```sql
WITH candidates AS (
    (
        SELECT id, aggregate_id, scheduled_at, created_at
        FROM inbox
        WHERE consumption = 'pull' AND source = :source AND state = 'pending'
          AND scheduled_at <= clock_timestamp()
        ORDER BY scheduled_at, created_at, id
        LIMIT :cand_limit
    )
    UNION ALL
    (
        SELECT id, aggregate_id, scheduled_at, created_at
        FROM inbox
        WHERE consumption = 'pull' AND source = :source AND state = 'processing'
          AND lease_expires_at <= clock_timestamp()
        ORDER BY scheduled_at, created_at, id
        LIMIT :cand_limit
    )
),
ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           row_number() OVER (
               PARTITION BY COALESCE(aggregate_id, '#' || id::text)
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT r.id, r.aggregate_id, r.scheduled_at, r.created_at
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = r.aggregate_id
              AND busy.source = :source
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    ORDER BY r.scheduled_at, r.created_at, r.id
    LIMIT :batch
),
locked AS (
    SELECT i.id FROM inbox AS i
    JOIN eligible AS e ON i.id = e.id
    WHERE i.consumption = 'pull' AND i.source = :source
      AND ((i.state = 'pending' AND i.scheduled_at <= clock_timestamp())
        OR (i.state = 'processing' AND i.lease_expires_at <= clock_timestamp()))
      AND (i.aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = i.aggregate_id
              AND busy.source = :source
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target
SET state = 'processing', claim_token = gen_random_uuid(), claimed_at = clock_timestamp(),
    lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
FROM locked
WHERE target.id = locked.id
  AND target.consumption = 'pull' AND target.source = :source
  AND ((target.state = 'pending' AND target.scheduled_at <= clock_timestamp())
    OR (target.state = 'processing' AND target.lease_expires_at <= clock_timestamp()))
  AND (target.aggregate_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM inbox AS busy
        WHERE busy.aggregate_id = target.aggregate_id
          AND busy.source = :source
          AND busy.consumption = 'pull'
          AND busy.state = 'processing'
          AND busy.lease_expires_at > clock_timestamp()))
RETURNING target.*;
```

### Final statement, SQL Server (adds the checked sp_getapplock return; supersedes round 2)
```sql
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
-- The local variables below (@qbBatch, @qbLeaseMs, @qbCandLimit) carry names that differ
-- from the bound parameters (:batch, :lease_ms, :cand_limit) on purpose. A driver that sends
-- a bound parameter to sp_executesql passes it as an argument of that name, and a DECLARE
-- cannot reuse an argument name in the same batch: Msg 134, "The variable name ... has
-- already been declared." A client library must render :batch, :lease_ms and :cand_limit
-- under a bound parameter name distinct from @qbBatch, @qbLeaseMs and @qbCandLimit, never
-- under those same names, or the claim fails on every call.
BEGIN TRANSACTION;
DECLARE @qbBatch INT = :batch;
DECLARE @qbLeaseMs INT = :lease_ms;
DECLARE @qbCandLimit INT = :cand_limit; -- LEAST(GREATEST(3 * @qbBatch, 50), 500)
DECLARE @src VARCHAR(255) = :source;
DECLARE @lockresult INT;
EXEC @lockresult = sp_getapplock @Resource = @src, @LockMode = 'Exclusive',
    @LockOwner = 'Transaction', @LockTimeout = 30000;
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
```

### Settled index set, PostgreSQL (supersedes every earlier recommendation in this document)
```sql
CREATE INDEX idx_inbox_pull_pending ON inbox (source, scheduled_at, created_at, id)
    WHERE consumption = 'pull' AND state = 'pending';
CREATE INDEX idx_inbox_pull_busy ON inbox (source, aggregate_id, lease_expires_at)
    WHERE consumption = 'pull' AND state = 'processing';
```
Do not create 'idx_inbox_pull_processing' or 'idx_inbox_agg_state_lease' from earlier rounds.

---

## Round 3 fix: a silent-failure defect, and gaps in what this document states

Re-review verdicted all four round-2 findings ADDRESSED. Three remaining items were gaps in
what THIS document states, not further defects, and have already been folded into the sections
above (the SQL Server statement now checks 'sp_getapplock''s return value; the ceiling and
dialect-asymmetry paragraph above states what an operator must do on each dialect; the wording
fixes above replace "same pattern" with "analogous" and relabel the 111.8/139.0 pair as a
noisy range). The fourth was a real defect, detailed here.

**'sp_getapplock' returns a negative value on failure without raising an error.** -1 on
lock-request timeout, -3 on being chosen as a deadlock victim, WITHOUT throwing. The round-2
statement captured the return code and ignored it, so past the 30-second timeout under load, or
on a deadlock-victim outcome, the claim would proceed completely unserialized — silently
reintroducing the exact deadlock the lock exists to prevent, with no signal to the caller.

**Fix, now in the statement above:** an explicit 'IF @lockresult < 0' check that rolls back and
'THROW's. 'THROW' was chosen over 'RAISERROR' because it always terminates the batch — there is
no path that logs a warning and falls through to the claim. A client library that sees this
error must treat it as transient, back off, and retry the whole call; it must NOT retry
immediately (round 2 already showed immediate retry can collapse throughput) and must NOT treat
it as a data or logic error.

**Proved by forcing a negative return.** Session A held 'sp_getapplock' on source 's' for 5
seconds. Session B, started 0.5 seconds later, ran the claim with 'LockTimeout = 1000', short
enough to expire while A still held it, against one seeded pending row. Session B raised
'Msg 51000' and returned no rows:
```
Msg 51000, Level 16, State 1, Server ...
inbox pull claim: sp_getapplock did not acquire the per-source claim lock
```
The seeded row was confirmed untouched afterward ('state = pending', 'claim_token = NULL').
The claim failed loudly rather than proceeding unserialized or silently doing nothing.

**The delayed-B probe was re-run against the actual shipped 'UNION ALL' statement,
superseding the earlier single-branch variant used in rounds 1 and 2.** Same result: worker A
committed a claim; worker B, whose 'pending'-branch read had already selected the same row
before A's commit, found it excluded once its own locking stage re-validated the repeated
predicate. Full transcript in the report's round 3 section.
