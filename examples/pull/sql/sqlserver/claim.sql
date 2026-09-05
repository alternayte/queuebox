WITH candidates AS (
    SELECT TOP (:batch) * FROM inbox WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE consumption = 'pull' AND source = :source
      AND ((state = 'pending' AND scheduled_at <= SYSUTCDATETIME())
        OR (state = 'processing' AND lease_expires_at <= SYSUTCDATETIME()))
    ORDER BY scheduled_at, created_at
)
UPDATE candidates
SET state = 'processing', claim_token = NEWID(), claimed_at = SYSUTCDATETIME(),
    lease_expires_at = DATEADD(millisecond, :lease_ms, SYSUTCDATETIME())
OUTPUT INSERTED.*;
