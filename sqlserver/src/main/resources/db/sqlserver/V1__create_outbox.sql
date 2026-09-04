-- Outbox table for reliable message publishing
IF OBJECT_ID('outbox', 'U') IS NULL
CREATE TABLE outbox (
    id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
    topic NVARCHAR(255) NOT NULL,
    [key] NVARCHAR(255),
    payload NVARCHAR(MAX) NOT NULL,
    headers NVARCHAR(MAX) NOT NULL DEFAULT '{}',
    state NVARCHAR(50) NOT NULL DEFAULT 'pending',
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    scheduled_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    updated_at DATETIME2 NOT NULL DEFAULT GETUTCDATE()
);

-- Filtered index for efficient pending message queries
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_outbox_pending_scheduled' AND object_id = OBJECT_ID('outbox'))
CREATE INDEX idx_outbox_pending_scheduled ON outbox(state, scheduled_at)
    WHERE state = 'pending';

-- Index for state-based cleanup operations
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_outbox_state_updated' AND object_id = OBJECT_ID('outbox'))
CREATE INDEX idx_outbox_state_updated ON outbox(state, updated_at);
