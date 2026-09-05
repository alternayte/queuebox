UPDATE inbox SET state = 'dead', last_error = :error, claim_token = NULL, lease_expires_at = NULL
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > clock_timestamp();
