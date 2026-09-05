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

## Policy

1. **One logical change per file.** Do not put two unrelated changes in one file.
2. **Never edit a released file.** Flyway records a checksum. A change to an applied file makes
   every existing deployment fail. Write a new version instead.
3. **Add the same version number to both databases.** If a change applies to one database only,
   still add a file with that version number to the other set, and write a comment that says the
   change does not apply there.
4. **Write every file so it can run twice safely.** This is mandatory, not a preference. An
   operator can create the schema by hand with the files below. Flyway then
   baselines the database at version 0 and replays every file. Use `CREATE TABLE IF NOT EXISTS`
   and `ADD COLUMN IF NOT EXISTS` on PostgreSQL. Use `IF OBJECT_ID(...) IS NULL` and
   `IF COL_LENGTH(...) IS NULL` on SQL Server. `PostgresMigratorTest` and `SqlServerMigratorTest`
   both replay every file against a schema that already exists.
5. **Do not use `GO`.** `GO` is a sqlcmd batch separator, not T-SQL. Use `EXEC('...')` when a
   statement must run after a schema change in the same file.

## Manual application

Set `database.migrate` to false and apply the SQL files by hand, in version order, with a
privileged user, in two cases.

1. The application user has no DDL rights.
2. The configuration renames a table or a column. The bundled files name the default schema, so
   QueueBox refuses to run them against a renamed schema and fails at startup with a named
   error. See `MigrationGuardTest`.

```bash
psql -f postgres/src/main/resources/db/postgresql/V1__create_outbox.sql
psql -f postgres/src/main/resources/db/postgresql/V2__create_inbox.sql
psql -f postgres/src/main/resources/db/postgresql/V3__add_claimed_at.sql
psql -f postgres/src/main/resources/db/postgresql/V4__add_last_error.sql
psql -f postgres/src/main/resources/db/postgresql/V5__add_correlation_id.sql
```

Apply EVERY file. An incomplete set does not fail at startup. It fails on the first insert, because
the row names a column the table does not hold. `MigrationParityTest` asserts that this document
lists every file that ships.

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
[the configuration reference](../configuration.md).

`V7__capture_state.sql` creates `queuebox_capture_state`. The table records the capture
identity, the state identifier and a fingerprint of the capture settings, so QueueBox can
detect a lost durable volume and a changed capture configuration. See
[the capture guide](../capture.md). The table is unused while capture is disabled, which
is the default.
