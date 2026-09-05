UPDATE inbox SET state = 'processed', processed_at = clock_timestamp(), claim_token = NULL, lease_expires_at = NULL
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > clock_timestamp();
