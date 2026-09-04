-- Inbox table for idempotent message processing
CREATE TABLE IF NOT EXISTS inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255),
    event_type VARCHAR(255),
    payload JSONB NOT NULL,
    state VARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,

    -- Unique constraint for deduplication
    CONSTRAINT uq_inbox_source_idempotency UNIQUE (source, idempotency_key)
);

-- Index for efficient claiming of pending messages
CREATE INDEX IF NOT EXISTS idx_inbox_pending ON inbox(state)
    WHERE state = 'pending';

-- Index for source lookups
CREATE INDEX IF NOT EXISTS idx_inbox_source ON inbox(source);

-- Index for efficient aggregate ordering queries
CREATE INDEX IF NOT EXISTS idx_inbox_aggregate_state ON inbox(aggregate_id, state);

COMMENT ON TABLE inbox IS 'Inbox for idempotent webhook/message processing';
COMMENT ON COLUMN inbox.source IS 'Message source identifier (e.g., stripe, github)';
COMMENT ON COLUMN inbox.idempotency_key IS 'Unique key within source for deduplication';
COMMENT ON COLUMN inbox.aggregate_id IS 'Optional aggregate identifier for ordered processing within an aggregate';
COMMENT ON COLUMN inbox.state IS 'Message state: pending, processing, processed';
