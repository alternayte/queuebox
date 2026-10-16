-- A downstream loader reads the rows that changed after its cursor, minus a lag. Every state
-- change sets updated_at, the change to dead included. Retention cleanup counts from it.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.
-- See docs/specs/updated-at-cursor.md.
ALTER TABLE inbox ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

-- Rows that exist before the upgrade get the latest of their known times. GREATEST ignores NULL.
UPDATE inbox
SET updated_at = GREATEST(created_at, scheduled_at, claimed_at, processed_at)
WHERE updated_at IS NULL;

-- clock_timestamp() is the statement time. CURRENT_TIMESTAMP is the transaction start, which
-- lets a long transaction write a value far behind its commit.
ALTER TABLE inbox ALTER COLUMN updated_at SET DEFAULT clock_timestamp();
ALTER TABLE inbox ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE outbox ALTER COLUMN updated_at SET DEFAULT clock_timestamp();

-- Serves the loader query and retention cleanup. CREATE INDEX locks out writes for the build,
-- as V8 notes.
CREATE INDEX IF NOT EXISTS idx_inbox_updated_at ON inbox (updated_at);
CREATE INDEX IF NOT EXISTS idx_outbox_updated_at ON outbox (updated_at);
