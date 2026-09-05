-- Stop all old workers before applying this claim contract.
ALTER TABLE inbox ADD claim_token UNIQUEIDENTIFIER NULL;
ALTER TABLE inbox ADD lease_expires_at DATETIME2 NULL;
ALTER TABLE outbox ADD claim_token UNIQUEIDENTIFIER NULL;
ALTER TABLE outbox ADD lease_expires_at DATETIME2 NULL;
ALTER TABLE inbox ADD consumption VARCHAR(4) NOT NULL DEFAULT 'push' CHECK (consumption IN ('push', 'pull'));
ALTER TABLE inbox ADD scheduled_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME();
ALTER TABLE inbox ADD attempt INT NOT NULL DEFAULT 0;
ALTER TABLE inbox ADD last_error NVARCHAR(MAX) NULL;
CREATE INDEX idx_inbox_consumption_pending ON inbox (consumption, state, scheduled_at);
