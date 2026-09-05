UPDATE inbox SET lease_expires_at = DATEADD(millisecond, :lease_ms, SYSUTCDATETIME())
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > SYSUTCDATETIME();
