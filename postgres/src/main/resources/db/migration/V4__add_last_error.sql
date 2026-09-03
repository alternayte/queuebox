-- F-016: record why a message was retried or dead-lettered.
-- The application truncates the text and redacts the known secret-bearing headers before it
-- writes the column.

ALTER TABLE outbox ADD COLUMN last_error TEXT;

COMMENT ON COLUMN outbox.last_error IS 'Why the last delivery attempt failed. Redacted and truncated.';
