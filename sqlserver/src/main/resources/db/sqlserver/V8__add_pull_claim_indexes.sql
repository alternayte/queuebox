CREATE INDEX idx_inbox_pull_pending ON inbox (source, scheduled_at, created_at, id)
    WHERE consumption = 'pull' AND state = 'pending';
CREATE INDEX idx_inbox_pull_busy ON inbox (source, aggregate_id, lease_expires_at)
    WHERE consumption = 'pull' AND state = 'processing';

-- KNOWN GAP: this index set does not cover the claim's second candidate branch, the
-- expired-lease reclaim path. That branch scans WHERE state = 'processing' AND
-- lease_expires_at <= now, ordered by scheduled_at, created_at, id. The idx_inbox_pull_busy
-- index above leads on aggregate_id, so the reclaim branch still scans the table and sorts the
-- result without an index hit. DECISION: do not add an index for this branch now. The reclaim
-- branch runs only when a lease expires, and the last index added to this table without
-- measurement had to be removed. FOLLOW-UP: before adding an index, measure the reclaim
-- branch's scan cost and row count under a real expired-lease workload. See
-- docs/build/spikes/2026-09-08-aggregate-reservation.md.

-- MAINTENANCE NOTE: CREATE INDEX above locks out inserts to inbox for the duration of the
-- build. Applying this migration to a populated database needs a maintenance window.
-- DECISION: do not change the migration strategy now. The online alternative for SQL Server
-- is CREATE INDEX ... WITH (ONLINE = ON), available on Enterprise Edition and Azure SQL. See
-- docs/development/migrations.md.

-- The filtered WHERE clause on each index above needs SET QUOTED_IDENTIFIER ON at create
-- time. The Microsoft JDBC driver, which Flyway uses to run this migration, sets that option
-- ON by default, so no action is needed here. A caller who applies this script through another
-- client must confirm the setting is ON before running it.
