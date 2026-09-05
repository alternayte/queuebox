WITH candidates AS (
    SELECT id FROM inbox
    WHERE consumption = 'pull' AND source = :source
      AND ((state = 'pending' AND scheduled_at <= clock_timestamp())
        OR (state = 'processing' AND lease_expires_at <= clock_timestamp()))
    ORDER BY scheduled_at, created_at
    LIMIT :batch
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target
SET state = 'processing', claim_token = gen_random_uuid(),
    claimed_at = clock_timestamp(),
    lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
FROM candidates WHERE target.id = candidates.id
RETURNING target.*;
