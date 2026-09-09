-- F-090. A destination can render its exchange from the row, and it needs a field to render from.
-- Debezium reads `aggregatetype` for the same purpose. The column is nullable, so the migration is
-- additive and an existing writer that never sets it still works.
IF COL_LENGTH('outbox', 'aggregate_type') IS NULL
    ALTER TABLE outbox ADD aggregate_type NVARCHAR(255) NULL;
