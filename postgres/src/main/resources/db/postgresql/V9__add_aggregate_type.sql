-- F-090. A destination can render its exchange from the row, and it needs a field to render from.
-- Debezium reads `aggregatetype` for the same purpose. The column is nullable, so the migration is
-- additive and an existing writer that never sets it keeps working.
ALTER TABLE outbox ADD COLUMN aggregate_type VARCHAR(255);
