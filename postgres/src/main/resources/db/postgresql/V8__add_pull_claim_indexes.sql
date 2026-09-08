CREATE INDEX idx_inbox_pull_pending ON inbox (source, scheduled_at, created_at, id)
    WHERE consumption = 'pull' AND state = 'pending';
CREATE INDEX idx_inbox_pull_busy ON inbox (source, aggregate_id, lease_expires_at)
    WHERE consumption = 'pull' AND state = 'processing';
