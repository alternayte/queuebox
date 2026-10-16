UPDATE inbox SET state = 'processed', processed_at = SYSUTCDATETIME(), claim_token = NULL, lease_expires_at = NULL, updated_at = SYSUTCDATETIME()
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > SYSUTCDATETIME();
