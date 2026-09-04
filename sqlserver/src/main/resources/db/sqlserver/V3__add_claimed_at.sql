-- F-006: recover messages that stay in state 'processing' after a crash.
-- claimed_at records the moment the poller or the relay claimed the row.
-- The reclaim step returns a row to 'pending' when claimed_at is older than the
-- visibility timeout.
--
-- Every statement is guarded, so the file can run against a schema that an operator already
-- created by hand.

IF COL_LENGTH('outbox', 'claimed_at') IS NULL
    ALTER TABLE outbox ADD claimed_at DATETIME2 NULL;

IF COL_LENGTH('inbox', 'claimed_at') IS NULL
    ALTER TABLE inbox ADD claimed_at DATETIME2 NULL;

-- The index statements run through EXEC, because T-SQL parses a batch before it runs it, and
-- the new column does not exist yet at parse time. EXEC avoids a GO batch separator, which is
-- a sqlcmd feature and not T-SQL.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_outbox_processing_claimed' AND object_id = OBJECT_ID('outbox'))
    EXEC('CREATE INDEX idx_outbox_processing_claimed ON outbox(claimed_at) WHERE state = ''processing''');

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_inbox_processing_claimed' AND object_id = OBJECT_ID('inbox'))
    EXEC('CREATE INDEX idx_inbox_processing_claimed ON inbox(claimed_at) WHERE state = ''processing''');
