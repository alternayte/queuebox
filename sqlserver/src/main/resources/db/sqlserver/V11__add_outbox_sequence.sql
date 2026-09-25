-- The claim delivers the rows of one key in insert order. created_at ties for rows that one
-- transaction inserts, so a counter gives the total order. Stop every replica that runs an older
-- claim before applying this migration, because the older claim ignores the key rule.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.
-- The statements after the ADD run through EXEC, because this batch compiles before the column
-- exists.
IF COL_LENGTH('outbox', 'sequence') IS NULL
    ALTER TABLE outbox ADD sequence BIGINT NULL;

-- Rows that exist before the upgrade keep their best known order.
EXEC('
DECLARE @base BIGINT = (SELECT COALESCE(MAX(sequence), 0) FROM outbox);
WITH ordered AS (
    SELECT sequence, ROW_NUMBER() OVER (ORDER BY created_at, id) AS n
    FROM outbox
    WHERE sequence IS NULL
)
UPDATE ordered SET sequence = @base + n;
');

IF OBJECT_ID('outbox_sequence_seq', 'SO') IS NULL
    CREATE SEQUENCE outbox_sequence_seq AS BIGINT START WITH 1 INCREMENT BY 1;

EXEC('
DECLARE @next BIGINT = (SELECT COALESCE(MAX(sequence), 0) + 1 FROM outbox);
DECLARE @restart NVARCHAR(200) = N''ALTER SEQUENCE outbox_sequence_seq RESTART WITH ''
    + CAST(@next AS NVARCHAR(20));
EXEC sp_executesql @restart;
');

IF OBJECT_ID('df_outbox_sequence', 'D') IS NULL
    EXEC('ALTER TABLE outbox ADD CONSTRAINT df_outbox_sequence
        DEFAULT (NEXT VALUE FOR outbox_sequence_seq) FOR sequence');

IF COLUMNPROPERTY(OBJECT_ID('outbox'), 'sequence', 'AllowsNull') = 1
    EXEC('ALTER TABLE outbox ALTER COLUMN sequence BIGINT NOT NULL');

-- Serves the head-row lookup of the claim: the live rows of one key, lowest sequence first.
-- A filtered index needs SET QUOTED_IDENTIFIER ON, which the driver sets.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_outbox_key_sequence' AND object_id = OBJECT_ID('outbox'))
    EXEC('CREATE INDEX idx_outbox_key_sequence ON outbox ([key], sequence)
        WHERE state IN (''pending'', ''processing'')');
