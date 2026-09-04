## What this pull request changes

<!-- Describe the change in two or three sentences. -->

## Why

<!-- State the problem that the change solves. Link the issue with "Closes #<number>". -->

## Type of change

- [ ] `feat` — a new capability
- [ ] `fix` — a correction of a defect
- [ ] `docs` — a documentation change only
- [ ] `chore` — build, tooling, or housekeeping

## How this was tested

<!-- Name the tests that cover the change. State the command that you ran. -->

## Checklist

- [ ] `./gradlew check` passes with a running Docker daemon.
- [ ] `./gradlew ktlintCheck detekt` passes.
- [ ] A test covers the change.
- [ ] The documentation is updated.
- [ ] `CHANGELOG.md` carries an entry under `Unreleased`.
- [ ] The commit messages follow the convention in `CONTRIBUTING.md`.
- [ ] The change adds no credential and no secret to the repository.

## Compatibility

- [ ] The configuration schema does not change.
- [ ] The configuration schema changes. The change is described above and follows
      `docs/development/releasing.md`.
- [ ] The database schema does not change.
- [ ] The database schema changes. A migration exists for PostgreSQL and for SQL Server, and it
      follows `docs/development/migrations.md`.
