-- F-016: record why a message was retried or dead-lettered.
-- The application truncates the text and redacts the known secret-bearing headers before it
-- writes the column.

IF COL_LENGTH('outbox', 'last_error') IS NULL
    ALTER TABLE outbox ADD last_error NVARCHAR(MAX) NULL;
