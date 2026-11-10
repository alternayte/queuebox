-- Issue #116. This change does not apply to SQL Server, which has no autovacuum. SQL Server
-- updates the statistics of a large table after about SQRT(1000 * rows) changes, so a backlog
-- refreshes them without a table setting. This file keeps the version numbers of the two sets
-- equal.
SELECT 1;
