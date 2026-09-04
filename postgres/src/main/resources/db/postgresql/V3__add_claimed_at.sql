-- F-006: recover messages that stay in state 'processing' after a crash.
-- claimed_at records the moment the poller or the relay claimed the row.
-- The reclaim step returns a row to 'pending' when claimed_at is older than the
-- visibility timeout.

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE inbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE;

-- Index that supports the reclaim query: state = 'processing' AND claimed_at < cutoff.
CREATE INDEX IF NOT EXISTS idx_outbox_processing_claimed ON outbox(claimed_at)
    WHERE state = 'processing';

CREATE INDEX IF NOT EXISTS idx_inbox_processing_claimed ON inbox(claimed_at)
    WHERE state = 'processing';

COMMENT ON COLUMN outbox.claimed_at IS 'When the poller claimed the row for processing';
COMMENT ON COLUMN inbox.claimed_at IS 'When the relay claimed the row for processing';

-- Index that supports the inbox claim: state = 'pending' ordered by created_at.
CREATE INDEX IF NOT EXISTS idx_inbox_state_created ON inbox(state, created_at);
