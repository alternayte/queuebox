UPDATE inbox SET lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > clock_timestamp();
