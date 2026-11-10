-- Issue #117. This change does not apply to SQL Server. The PostgreSQL file replaces the pending
-- indexes that the PostgreSQL planner chose for the pull claim after a stale ANALYZE. SQL Server
-- updates statistics on its own threshold, and no plan from SQL Server shows the fault. This file
-- keeps the version numbers of the two sets equal.
SELECT 1;
