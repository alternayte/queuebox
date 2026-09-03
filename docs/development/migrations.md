# Database migrations

QueueBox ships one migration set per supported database. Flyway applies them at startup when
`database.migrate` is true, which is the default.

| Location | Database |
|----------|----------|
| `postgres/src/main/resources/db/migration` | PostgreSQL |
| `sqlserver/src/main/resources/db/migration` | SQL Server |

## The two sets correspond one to one

Each version number describes the same logical change on both databases.

| Version | Change |
|---------|--------|
| `V1__create_outbox.sql` | Create the outbox table and its indexes. |
| `V2__create_inbox.sql` | Create the inbox table, its unique deduplication constraint, and its indexes. |
| `V3__add_claimed_at.sql` | Add `claimed_at` to both tables, so a crashed claim can be recovered. |
| `V4__add_last_error.sql` | Add `last_error` to the outbox, so an operator can see why a delivery failed. |

## Policy

1. **One logical change per file.** Do not put two unrelated changes in one file.
2. **Never edit a released file.** Flyway records a checksum. A change to an applied file makes
   every existing deployment fail. Write a new version instead.
3. **Add the same version number to both databases.** If a change applies to one database only,
   still add a file with that version number to the other set, and write a comment that says the
   change does not apply there.
4. **Write the file so it can run twice safely where the database allows it.** Prefer
   `IF NOT EXISTS` where the database supports it.
5. **Do not use `GO`.** `GO` is a sqlcmd batch separator, not T-SQL. Use `EXEC('...')` when a
   statement must run after a schema change in the same file.

## Manual application

An operator whose application user has no DDL rights must set `database.migrate` to false and
apply the SQL files by hand, in version order, with a privileged user.

```bash
psql -f postgres/src/main/resources/db/migration/V1__create_outbox.sql
psql -f postgres/src/main/resources/db/migration/V2__create_inbox.sql
psql -f postgres/src/main/resources/db/migration/V3__add_claimed_at.sql
psql -f postgres/src/main/resources/db/migration/V4__add_last_error.sql
```

## History note

The SQL Server set once held one file, `V1__create_tables.sql`, that created both tables. Finding
F-031 split it, so the two sets correspond one to one. The split happened before QueueBox applied
any migration automatically, so no deployment carried a Flyway checksum for the old file.
