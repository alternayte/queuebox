-- A downstream loader reads the rows that changed after its cursor, minus a lag. Every state
-- change sets updated_at, the change to dead included. Retention cleanup counts from it.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.
-- The statements after the ADD run through EXEC, because this batch compiles before the column
-- exists. See docs/specs/updated-at-cursor.md.
IF COL_LENGTH('inbox', 'updated_at') IS NULL
    ALTER TABLE inbox ADD updated_at DATETIME2 NULL;

-- Rows that exist before the upgrade get the latest of their known times. MAX ignores NULL.
-- GREATEST needs SQL Server 2022, so a VALUES list stands in for it.
EXEC('
UPDATE inbox
SET updated_at = (
    SELECT MAX(moment)
    FROM (VALUES (created_at), (scheduled_at), (claimed_at), (processed_at)) AS moments(moment)
)
WHERE updated_at IS NULL;
');

IF OBJECT_ID('df_inbox_updated_at', 'D') IS NULL
    EXEC('ALTER TABLE inbox ADD CONSTRAINT df_inbox_updated_at DEFAULT (SYSUTCDATETIME()) FOR updated_at');

IF COLUMNPROPERTY(OBJECT_ID('inbox'), 'updated_at', 'AllowsNull') = 1
    EXEC('ALTER TABLE inbox ALTER COLUMN updated_at DATETIME2 NOT NULL');

-- Serves the loader query and retention cleanup. The outbox already has idx_outbox_state_updated
-- from V1, and its default is already the statement time.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_inbox_updated_at' AND object_id = OBJECT_ID('inbox'))
    EXEC('CREATE INDEX idx_inbox_updated_at ON inbox (updated_at)');
