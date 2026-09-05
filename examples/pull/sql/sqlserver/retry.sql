UPDATE inbox SET state = 'pending', scheduled_at = DATEADD(millisecond, :delay_ms, SYSUTCDATETIME()), attempt = attempt + 1, last_error = :error, claim_token = NULL, lease_expires_at = NULL
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > SYSUTCDATETIME();
