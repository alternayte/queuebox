-- Outbox table for transactional outbox pattern
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic VARCHAR(255) NOT NULL,
    key VARCHAR(255),
    payload JSONB NOT NULL,
    state VARCHAR(50) NOT NULL DEFAULT 'pending',
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for efficient claiming of pending messages
-- This index supports the claimBatch query: state = 'pending' AND scheduled_at <= now()
CREATE INDEX idx_outbox_pending_scheduled ON outbox(state, scheduled_at)
    WHERE state = 'pending';

-- Index for topic-based queries
CREATE INDEX idx_outbox_topic ON outbox(topic);

COMMENT ON TABLE outbox IS 'Transactional outbox for reliable message publishing';
COMMENT ON COLUMN outbox.state IS 'Message state: pending, processing, sent, failed, dead';
COMMENT ON COLUMN outbox.scheduled_at IS 'When the message should be processed (supports delayed/retry scheduling)';
