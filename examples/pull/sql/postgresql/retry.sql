UPDATE inbox SET state = 'pending', scheduled_at = clock_timestamp() + :delay_ms * INTERVAL '1 millisecond', attempt = attempt + 1, last_error = :error, claim_token = NULL, lease_expires_at = NULL
WHERE id = :id AND consumption = 'pull' AND state = 'processing'
  AND claim_token = :token AND lease_expires_at > clock_timestamp();
