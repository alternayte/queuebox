-- Issue #117. The pull claim must have one index for its pending branch: idx_inbox_pull_pending
-- from V8. Two older indexes also hold every pending pull row, and the planner chose each of them
-- in production. idx_inbox_pending holds every pending row in no useful order, so after a stale
-- ANALYZE the planner read and sorted the whole backlog on each claim. idx_inbox_consumption_pending
-- leads on consumption and state, so it competes with V8 for pull rows as well.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.

-- Serves the push claim: pending push rows in claim order. It holds no pull row, so the pull claim
-- cannot choose it. Create it before the drop, so the push claim always has an index.
-- CREATE INDEX locks out writes for the build, as V8 notes.
CREATE INDEX IF NOT EXISTS idx_inbox_push_pending ON inbox (scheduled_at, created_at)
    WHERE consumption = 'push' AND state = 'pending';

DROP INDEX IF EXISTS idx_inbox_consumption_pending;

-- The pending count and the oldest pending age read idx_inbox_state_created from V3.
DROP INDEX IF EXISTS idx_inbox_pending;
