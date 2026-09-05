# Pull workers

Run QueueBox with `queuebox.yml` against the PostgreSQL service in the root Compose
setup (adjust credentials to match that database). For SQL Server, set `database.type`,
URL and credentials as in the existing SQL Server configuration example.
POST `{"id":"order-1","amount":10}` to `/inbox/orders`.
No route, destination or topic is required for this source.

The SQL files under `sql/postgresql` and `sql/sqlserver` are prepared statement
contracts. Bind named parameters through your database library; do not interpolate
values. `source` is the configured source name, `batch` is available worker capacity,
`lease_ms` is a positive duration, `id` and `token` come from the claim result.
Run claim in a short transaction and commit before starting work. SQL Server examples
use READ COMMITTED with READ_COMMITTED_SNAPSHOT disabled. Use the corresponding
READCOMMITTEDLOCK hint if your database enables read-committed snapshot isolation.

Renew every third of the lease duration. A renewal, completion, retry or dead-letter
update must affect exactly one row. Zero means ownership was lost: stop work and
never use a different token. Retry increments `attempt`; use `dead.sql` once the
application's retry ceiling is reached. Sanitize and truncate `error` before storing it.

For transactional business changes:

1. Begin a transaction on the same database connection as the business writes.
2. Apply the business changes.
3. Execute `complete.sql` with the original token.
4. If the affected count is not exactly one, **roll back the whole transaction**.
5. Otherwise commit both the business changes and completion together.

The message can be claimed again after lease expiry, including when a worker dies.
External work cannot share this transaction and can happen more than once. Use the
source-qualified identity `(source, idempotency_key)` to deduplicate external effects
and replay; different sources can legitimately use the same event ID.
