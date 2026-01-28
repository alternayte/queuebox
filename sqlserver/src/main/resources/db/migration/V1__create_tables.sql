-- Outbox table for reliable message publishing
CREATE TABLE outbox (
    id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
    topic NVARCHAR(255) NOT NULL,
    [key] NVARCHAR(255),
    payload NVARCHAR(MAX) NOT NULL,
    state NVARCHAR(50) NOT NULL DEFAULT 'pending',
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    scheduled_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    updated_at DATETIME2 NOT NULL DEFAULT GETUTCDATE()
);

-- Filtered index for efficient pending message queries
CREATE INDEX idx_outbox_pending_scheduled ON outbox(state, scheduled_at)
    WHERE state = 'pending';

-- Index for state-based cleanup operations
CREATE INDEX idx_outbox_state_updated ON outbox(state, updated_at);

-- Inbox table for idempotent message processing
CREATE TABLE inbox (
    id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
    source NVARCHAR(255) NOT NULL,
    idempotency_key NVARCHAR(255) NOT NULL,
    event_type NVARCHAR(255),
    payload NVARCHAR(MAX) NOT NULL,
    state NVARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    processed_at DATETIME2

    -- Unique constraint for idempotency
    CONSTRAINT uq_inbox_source_idempotency UNIQUE (source, idempotency_key)
);

-- Index for pending message processing
CREATE INDEX idx_inbox_state ON inbox(state);

-- Index for state-based cleanup operations
CREATE INDEX idx_inbox_state_created ON inbox(state, created_at);
