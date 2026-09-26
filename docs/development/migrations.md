# Database migrations

QueueBox ships one migration set per supported database. Flyway applies them at startup when
`database.migrate` is true, which is the default.

| Location | Database |
|----------|----------|
| `postgres/src/main/resources/db/postgresql` | PostgreSQL |
| `sqlserver/src/main/resources/db/sqlserver` | SQL Server |

## The two sets correspond one to one

Each version number describes the same logical change on both databases.

| Version | Change |
|---------|--------|
| `V1__create_outbox.sql` | Create the outbox table and its indexes. |
| `V2__create_inbox.sql` | Create the inbox table, its unique deduplication constraint, and its indexes. |
| `V3__add_claimed_at.sql` | Add `claimed_at` to both tables, so a crashed claim can be recovered. |
| `V4__add_last_error.sql` | Add `last_error` to the outbox, so an operator can see why a delivery failed. |
| `V5__add_correlation_id.sql` | Add `correlation_id` to the inbox, so one identifier follows a message through every log line. |
| `V6__add_consumption_and_leases.sql` | Add `consumption`, `claim_token`, `lease_expires_at`, and the inbox schedule, attempt and error columns. See [Upgrading to the claim contract of V6](#upgrading-to-the-claim-contract-of-v6). |
| `V7__capture_state.sql` | Create `queuebox_capture_state`, so QueueBox can detect a lost durable volume and a changed capture configuration. |
| `V8__add_pull_claim_indexes.sql` | Add the two indexes that the pull claim statement needs, one for a pending row of a source and one for a busy aggregate of a source. |
| `V9__add_aggregate_type.sql` | Add the nullable `aggregate_type` column to the outbox, so a destination can render its exchange from the row. See F-090. |
| `V10__add_inbox_headers.sql` | Add the `headers` column to the inbox (`JSONB` on PostgreSQL, `NVARCHAR(MAX)` on SQL Server, `NOT NULL`, default `'{}'`), so the inbox keeps the received message headers. A custom inbox table must add the column by hand, because QueueBox stops at startup without it. |
| `V11__add_outbox_sequence.sql` | Add the `sequence` column to the outbox (`BIGINT NOT NULL`, filled by the `outbox_sequence_seq` sequence), backfill existing rows in `created_at` order, and add `idx_outbox_key_sequence`. The claim orders the rows of one key by it. Stop every replica that runs an older claim before applying it. A custom outbox table must add the column by hand, because QueueBox stops at startup without it. |

## Policy

1. **One logical change per file.** Do not put two unrelated changes in one file.
2. **Never edit a released file.** Flyway records a checksum. A change to an applied file makes
   every existing deployment fail. Write a new version instead.
3. **Add the same version number to both databases.** If a change applies to one database only,
   still add a file with that version number to the other set, and write a comment that says the
   change does not apply there.
4. **Do not use `GO`.** `GO` is a sqlcmd batch separator, not T-SQL. Use `EXEC('...')` when a
   statement must run after a schema change in the same file.

## Installs without DDL rights

`queuebox migrate` is the one path for an install whose application user has no DDL rights. It
applies the bundled migrations with the same image, configuration and Flyway history as the
startup migration, logs the versions it applied, and exits. It exits 0 after success and
non-zero after a failure.

1. Run `queuebox migrate` with a privileged user before each upgrade, for example
   `docker run --rm -e QUEUEBOX_DATABASE_URL=... ghcr.io/alternayte/queuebox:<version> migrate`.
2. Run the service with `database.migrate: false` and the application user.

The command ignores `database.migrate`. It refuses a configuration that renames a table or a
column, as the startup migration does. The bundled files name the default schema, so a renamed
schema keeps its own SQL and the startup schema guards. See `MigrationGuardTest`.

## A database without a migration history

An empty database, or one that holds only application tables, baselines at version 0 and
migrates. A database that holds an `outbox` or an `inbox` table and no Flyway history stops the
startup migration and `queuebox migrate` with this message:

```text
QueueBox tables exist without a migration history. Run `queuebox migrate --baseline <version>` with the last version you applied by hand.
```

`queuebox migrate --baseline <version>` records `<version>` as applied without running it or an
earlier file. It then applies the later files. It refuses a database that already has a history,
because a second baseline would hide the real state. `PostgresMigratorTest` and
`SqlServerMigratorTest` cover the stop and the baseline.

## History note

The SQL Server set once held one file, `V1__create_tables.sql`, that created both tables. Finding
F-031 split it, so the two sets correspond one to one. The split happened before QueueBox applied
any migration automatically, so no deployment carried a Flyway checksum for the old file.

## Upgrading to the claim contract of V6

`V6__add_consumption_and_leases.sql` adds `consumption`, `claim_token` and
`lease_expires_at`, plus the inbox schedule, attempt and error columns. Every change is
additive, so the old columns keep their meaning and no data is rewritten.

The old worker fences a claim on a timestamp; the new worker fences it on an opaque token
and an unexpired lease. The two contracts must not run at the same time, because an old
worker can complete a row that a new worker owns. Upgrade in this order:

1. Stop every QueueBox worker of the old version.
2. Apply `V6__add_consumption_and_leases.sql`, then `V7__capture_state.sql`.
3. Start the workers of the new version.

Existing inbox rows migrate as `push`, which keeps the previous behaviour. A custom schema
must add and map the new columns by hand; see the column mapping in
[the configuration reference](https://queuebox-docs.pages.dev/reference/configuration/).

`V7__capture_state.sql` creates `queuebox_capture_state`. The table records the capture
identity, the state identifier and a fingerprint of the capture settings, so QueueBox can
detect a lost durable volume and a changed capture configuration. See
[the capture guide](https://queuebox-docs.pages.dev/how-to/capture-changes/). The table is unused while capture is disabled, which
is the default.

## Applying V8 to a populated database

`V8__add_pull_claim_indexes.sql` uses plain `CREATE INDEX`. On both engines this locks out
inserts to `inbox` for the duration of the build. Applying V8 to a populated database needs a
maintenance window.

The online alternative differs by engine. On SQL Server, use `CREATE INDEX ... WITH (ONLINE =
ON)`, available on Enterprise Edition and on Azure SQL. On PostgreSQL, use `CREATE INDEX
CONCURRENTLY`, run outside a transaction block. QueueBox does not use either alternative today;
see the note in each `V8__add_pull_claim_indexes.sql` file.

## Upgrading to the key order of V11

`V11__add_outbox_sequence.sql` adds the outbox `sequence` column. The claim of this version
delivers the rows of one non-empty `key` in `sequence` order, one row of a key at a time. The
claim of an older version ignores the key, so the two versions must not claim at the same time.
Upgrade in this order:

1. Stop every QueueBox worker of the old version.
2. Apply `V11__add_outbox_sequence.sql`. It numbers the existing rows in `created_at, id` order.
3. Start the workers of the new version.

A custom outbox table must add a `BIGINT` column that the database fills on insert, and map it
as `database.columnMapping.outbox.sequence`. QueueBox stops at startup without it and prints the
`ALTER TABLE` statement. See [ordering](https://queuebox-docs.pages.dev/concepts/ordering/#order-and-the-key).
