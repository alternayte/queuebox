-- Issue #116. Most rows of inbox and outbox are finished rows that retention keeps. The default
-- autovacuum thresholds are a fraction of the table, so a backlog that changes the claim plan
-- does not start an auto-analyze, and the planner keeps statistics from an empty queue. These
-- values start an analyze after about 0.5 % of the table changes and a vacuum after about 1 %
-- of the table is dead.
-- A per-table value that the operator set before this migration stays. The block sets only the
-- keys that the table does not carry yet.
-- Every statement is safe to run twice, because an operator can apply this file by hand first.
DO $$
DECLARE
    wanted CONSTANT text[] := ARRAY[
        'autovacuum_analyze_scale_factor=0.005',
        'autovacuum_analyze_threshold=1000',
        'autovacuum_vacuum_scale_factor=0.01',
        'autovacuum_vacuum_threshold=1000'
    ];
    tbl text;
    present text[];
    missing text[];
BEGIN
    FOREACH tbl IN ARRAY ARRAY['inbox', 'outbox'] LOOP
        SELECT COALESCE(array_agg(split_part(opt, '=', 1)), '{}')
        INTO present
        FROM pg_class AS c, unnest(c.reloptions) AS opt
        WHERE c.oid = tbl::regclass;

        SELECT array_agg(w)
        INTO missing
        FROM unnest(wanted) AS w
        WHERE split_part(w, '=', 1) <> ALL (present);

        IF missing IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I SET (%s)', tbl, array_to_string(missing, ', '));
        END IF;
    END LOOP;
END
$$;
