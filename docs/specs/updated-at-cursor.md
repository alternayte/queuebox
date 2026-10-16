# updated_at cursor

## What it does
The `inbox` table and the `outbox` table get an `updated_at` column. QueueBox sets it on each state change of a row. A downstream loader reads `WHERE updated_at > cursor - lag` and gets every row that changed after the cursor, the change to `dead` included. Retention cleanup counts the retention period from `updated_at`, so a loader sees the final state of each row before cleanup deletes it. Issue #100.

## Decisions
- Migration V12 on Postgres and SQL Server adds `updated_at` to both tables: `TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` on Postgres and `DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()` on SQL Server — the same types as `scheduled_at`. `CURRENT_TIMESTAMP` on Postgres is the transaction start, which widens the gap by the length of the application transaction that inserts an outbox row.
- V12 backfills existing rows with `GREATEST(created_at, scheduled_at, claimed_at, processed_at)`, ignoring nulls, and adds a btree index on `updated_at` to each table — the loader query and the cleanup query need it.
- Every statement in V12 is safe to run twice, in the V11 style — an operator can apply the file by hand first.
- The cursor is a timestamp with a documented lag, not an exact cursor — a value written before commit can commit after a later value. Loaders such as dlt have a lag setting built in.
- QueueBox writes `clock_timestamp()` on Postgres and `SYSUTCDATETIME()` on SQL Server — the repositories already use these functions for the database clock.
- These writes set `updated_at`: store, store dead, claim, reclaim of a stale claim, processed or sent, dead, defer, and retry. This applies to both the push path and the pull path.
- The pull path writes live in the Go, TypeScript and C# clients. Each client adds `updatedAt` to its schema mapping and sets `updated_at` in claim, complete, retry and dead — write logic stays in application code, with no trigger. Each client gets a minor release that requires the V12 schema. Pull rows move the cursor only for consumers on those client versions, and the docs state this.
- Lease renewal does not set `updated_at` — a renewal changes neither the state nor the content, and a long handler renews many times.
- The application inserts outbox rows without `updated_at` and gets the default — application code and the EF Core mapping need no change.
- `InboxColumnMapping` and `OutboxColumnMapping` get a required `updatedAt` entry. `SchemaGuard` stops the start when the column is missing and prints the `ALTER TABLE` statement — the same rule as `headers` and `sequence`.
- Retention cleanup selects rows by `updated_at < cutoff` instead of `created_at < cutoff` — a row that finishes after a long retry, or a replayed dead row, is kept for the full retention period after its last change.
- The changelog and the migration docs state the upgrade gap. A 0.8.x replica does not set `updated_at`. The cursor is complete only after the last 0.8.x replica stops. A loader that runs during the rollout does one full reload after it — no trigger, because write logic stays in the application.
- The docs state the loader contract: use a lag longer than the longest transaction that writes rows, and keep the lag plus the load interval shorter than the retention period. On the outbox, that includes the longest application transaction that inserts outbox rows — QueueBox does not control it.
- V12 keeps the backfill, although it rewrites every row — without it, every old row carries the migration time, and cleanup deletes nothing for one retention period.
- The release is 0.9.0 — `docs/development/releasing.md` puts a column added by a migration in a MINOR release.

## Out
- An exact cursor in commit order (`xid8` with `pg_snapshot_xmin`, `rowversion` with `MIN_ACTIVE_ROWVERSION`).
- Visibility of deletes. A loader that needs deletes uses CDC.
- `updated_at` in `InboxMessage`, the pull API responses, and the message types of the Go, TypeScript and C# clients.
- A database trigger that sets `updated_at`.

## How I know it works
- After each transition of an inbox row (store, claim, reclaim, processed, defer, retry, dead), `SELECT updated_at` returns a value larger than the value before the transition.
- After each transition of an outbox row (claim, reclaim, sent, retry, dead), the same holds.
- After a lease renewal, `updated_at` does not change.
- A pull consumer on the new Go, TypeScript or C# client moves `updated_at` on claim, complete, retry and dead, on Postgres and SQL Server.
- On a database at V11 with rows, `queuebox migrate` applies V12. Every row has a non-null `updated_at` equal to the largest of its four time columns. Running the V12 file again by hand gives no error.
- A start against a custom table without the column stops, and the log prints the `ALTER TABLE` statement.
- Retention cleanup keeps a row with an old `created_at` and a recent `updated_at`, and deletes the row after `updated_at` passes the cutoff.
- `./gradlew check detekt` passes with Docker running.
