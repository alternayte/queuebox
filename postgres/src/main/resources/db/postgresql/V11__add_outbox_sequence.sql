-- The claim delivers the rows of one key in insert order. created_at ties for rows that one
-- transaction inserts, so a counter gives the total order. Stop every replica that runs an older
-- claim before applying this migration, because the older claim ignores the key rule.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS sequence BIGINT;

-- Rows that exist before the upgrade keep their best known order.
UPDATE outbox AS target
SET sequence = ordered.base + ordered.n
FROM (
    SELECT id,
           row_number() OVER (ORDER BY created_at, id) AS n,
           (SELECT COALESCE(MAX(sequence), 0) FROM outbox) AS base
    FROM outbox
    WHERE sequence IS NULL
) AS ordered
WHERE target.id = ordered.id;

CREATE SEQUENCE IF NOT EXISTS outbox_sequence_seq AS BIGINT OWNED BY outbox.sequence;
SELECT setval('outbox_sequence_seq', COALESCE((SELECT MAX(sequence) FROM outbox), 0) + 1, false);
ALTER TABLE outbox ALTER COLUMN sequence SET DEFAULT nextval('outbox_sequence_seq');
ALTER TABLE outbox ALTER COLUMN sequence SET NOT NULL;

-- Serves the head-row lookup of the claim: the live rows of one key, lowest sequence first.
CREATE INDEX IF NOT EXISTS idx_outbox_key_sequence ON outbox (key, sequence)
    WHERE state IN ('pending', 'processing');
