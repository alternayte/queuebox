-- F-016: record why a message was retried or dead-lettered.
-- The application truncates the text and redacts the known secret-bearing headers before it
-- writes the column.

ALTER TABLE outbox ADD last_error NVARCHAR(MAX) NULL;
