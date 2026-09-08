-- The claim takes at most one message per aggregate. A row whose aggregate already holds a
-- message in state 'processing' under a live lease is not a candidate, and two rows of one
-- aggregate never leave one claim together.
--
-- :cand_limit is a caller-computed bound on the candidate scan per branch, sized by the rule
-- cand_limit = LEAST(GREATEST(3 * :batch, 50), 500). It is not a tuning knob left to the
-- operator; the client library computes and binds it from :batch on every call.
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
