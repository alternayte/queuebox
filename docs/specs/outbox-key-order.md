# Outbox key order

## What it does
The outbox delivers the rows of one non-empty `key` to the destination in insert order, at any `outbox.concurrency` and with any number of replicas. One row of a key is in flight at a time. A row that waits for a retry holds back the later rows of its key. Rows with a null or empty `key` keep today's unordered, parallel delivery. Fixes issue #58.

## Decisions
- Every row with a non-empty `key` is ordered. No config switch exists — the Kafka publisher already uses `key` as the partition key, and one code path is easier to prove.
- Migration V11 adds a `sequence` column (`BIGINT NOT NULL`) to the outbox table on Postgres and SQL Server. A database sequence object fills it by default — `created_at` ties inside one transaction, and only a counter gives a total order.
- V11 backfills existing rows in `created_at, id` order before it sets the default and `NOT NULL` — rows that are pending at the upgrade keep their best known order.
- `OutboxColumnMapping` gets a required `sequence` entry. `SchemaGuard` fails the start when the column is missing and prints the `ALTER TABLE` statement — this is the same rule as the inbox `headers` column.
- The claim takes only the head row of each key. The head row is the lowest-`sequence` row of the key in state `pending` or `processing`, whatever its `scheduled_at` or lease — so a row in retry backoff or in flight blocks its successors.
- The claim takes the head row when it is `pending` and due. The claim never takes a `processing` row; `reclaimStale` still returns an expired row to `pending` — the claim and the reclaim keep their current split.
- The claim orders by `scheduled_at, sequence` for the batch, and the per-key head uses `sequence` alone — ties go away, and a due retry of one key does not jump the rows of other keys.
- The claim uses the head-row statement shape from `docs/build/spikes/2026-09-08-aggregate-reservation.md`, with the row lock on the base table (`FOR UPDATE SKIP LOCKED` on Postgres, `UPDLOCK, READPAST, ROWLOCK` on SQL Server) — both dialects already passed the concurrent-claimer arrangements with this shape.
- A row that goes `dead` releases its key, and the next row of the key is delivered — one poison row must not stall a stream. A replayed dead row keeps its `sequence`, so it becomes the head again, but it reaches the destination after the rows that passed it.
- `OutboxPoller` does not change — a claim never returns two rows of one key.
- A new index on `(key, sequence)` filtered to `pending` and `processing` serves the head-row lookup — the claim must not scan sent rows.
- Order holds for rows that one writer inserts per key. Two transactions that write the same key at the same time can commit out of `sequence` order, and the docs say so — a poller reads commit results, not a log.
- `docs/delivery-semantics.md` states the outbox rule next to the inbox rule. The README drops "only while concurrency is one".
- The changelog marks the required column as a breaking change — a custom outbox table needs an `ALTER TABLE`, and old replicas must stop before V11, because they ignore the key rule.

## Out
- The inbox push relay and pull claims. They keep their current `aggregate_id` rules and their `created_at` order.
- A run-per-key claim that publishes several rows of one key in one claim.
- Order between different keys.
- Order across a replay of a dead row.
- A config switch to turn ordering off.

## How I know it works
- Three rows of one key inserted in one transaction, with `concurrency: 8`, reach the destination in insert order on Postgres and on SQL Server.
- Two replicas that share one database deliver 200 rows over 10 keys, and each key arrives in insert order.
- A publisher that fails the first row of a key once: the second and third rows stay `pending` until the first row is `sent`, and all three arrive in order.
- A first row that goes `dead`: the second row is then delivered.
- Rows with an empty `key` are still claimed together in one batch and published in parallel.
- After V11 on a database with pending rows, `sequence` follows `created_at` order and new rows take higher values.
- A custom outbox table without `sequence` stops the start with a message that names the column and gives the `ALTER TABLE` statement.
- `./gradlew check detekt` passes.
