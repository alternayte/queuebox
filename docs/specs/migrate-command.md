# Migrate command

## What it does
`queuebox migrate` applies the bundled migrations and exits, with the same image, configuration and Flyway history as the startup migration. A privileged operator runs it once per upgrade, and the application runs with `database.migrate: false` and no DDL rights. `queuebox migrate --baseline <version>` records the history of a database whose files an operator applied by hand. Fixes #66.

## Decisions
- The released migration files stay unchanged — an edited file breaks the checksum of every existing install.
- `main` dispatches on its first argument: none starts the service, `migrate` runs the migration and exits — the image `ENTRYPOINT` is `./bin/app`, so `docker run <image> migrate` works with no image change.
- `migrate` reads the same configuration as the service and ignores `database.migrate` — the command is the explicit request.
- `migrate` refuses a renamed table or column through the existing migration guard — the bundled files name the default schema.
- `migrate` exits 0 after success and non-zero after a failure, and it logs the versions it applied — a job runner branches on the exit code.
- An empty database, or one that holds only application tables, baselines at version 0 and migrates, as today — QueueBox shares the application database.
- With no Flyway history and an existing `outbox` or `inbox` table, the startup migration and `migrate` both stop with: "QueueBox tables exist without a migration history. Run `queuebox migrate --baseline <version>` with the last version you applied by hand." — a guessed version can skip a migration without a sign.
- `migrate --baseline <version>` records `<version>` as applied without running it or any earlier file, then applies the later files — it turns a hand-applied install into a normal one.
- `--baseline` refuses a database that already has a history — a second baseline would hide the real state.
- `docs/development/migrations.md` drops rule 4 ("every file runs twice") and the `psql -f` list. It names `queuebox migrate` as the one path for an install without DDL rights.
- The docs site replaces the manual steps on the deploy, upgrade and custom-tables pages with the command. A custom schema keeps its hand-written SQL and the startup schema guards.
- The changelog lists `migrate` as added in 0.5.0, and names the new stop for a hand-applied database as breaking with its fix.

## Out
- Rollback or down migrations.
- A command that prints the pending SQL.
- Migrations for a custom table or column mapping.
- Detecting the applied version from the columns.

## How I know it works
- `docker run --rm -e QUEUEBOX_DATABASE_URL=… ghcr.io/alternayte/queuebox:0.5.0 migrate` on an empty database applies every version, prints them, and exits 0. A second run applies nothing and exits 0.
- The service then starts with `database.migrate: false` under a user without DDL rights.
- A database with the V1–V10 files applied by hand and no history stops the start with the baseline message.
- `migrate --baseline 10` on that database records V1–V10, applies V11, and the service starts.
- `migrate --baseline 5` on a database with a history exits non-zero and changes nothing.
- A database with application tables only migrates at start as before.
- `./gradlew check detekt` passes.
