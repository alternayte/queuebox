-- Inbox table for idempotent message processing
IF OBJECT_ID('inbox', 'U') IS NULL
CREATE TABLE inbox (
    id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
    source NVARCHAR(255) NOT NULL,
    idempotency_key NVARCHAR(255) NOT NULL,
    aggregate_id NVARCHAR(255),
    event_type NVARCHAR(255),
    payload NVARCHAR(MAX) NOT NULL,
    state NVARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    processed_at DATETIME2,

    -- Unique constraint for idempotency
    CONSTRAINT uq_inbox_source_idempotency UNIQUE (source, idempotency_key)
);

-- Index for pending message processing
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_inbox_state' AND object_id = OBJECT_ID('inbox'))
CREATE INDEX idx_inbox_state ON inbox(state);

-- Index for state-based cleanup operations
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_inbox_state_created' AND object_id = OBJECT_ID('inbox'))
CREATE INDEX idx_inbox_state_created ON inbox(state, created_at);

-- Index for efficient aggregate ordering queries
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_inbox_aggregate_state' AND object_id = OBJECT_ID('inbox'))
CREATE INDEX idx_inbox_aggregate_state ON inbox(aggregate_id, state);
