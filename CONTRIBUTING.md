# Contributing to QueueBox

Thank you for your interest in QueueBox. This document tells you how to build the project, how to
test it, and how to submit a change.

Every contributor must follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report a security defect
through [SECURITY.md](SECURITY.md), not through a public issue.

## Prerequisites

| Tool | Version | Reason |
|------|---------|--------|
| JDK | 21 (long term support) | The Gradle toolchain targets Java 21. |
| Docker | A running daemon | The integration and end-to-end tests start containers. |
| Git | Any recent version | Source control. |

The Gradle wrapper installs Gradle 8.13. Do not install Gradle by hand. Always call `./gradlew`.

The Foojay toolchain resolver downloads a Java 21 toolchain when the local JDK is a different
release. The first build therefore needs network access.

Docker must run before you call `./gradlew build` or `./gradlew check`. The tests use Testcontainers
with PostgreSQL, SQL Server and RabbitMQ. Without a Docker daemon those tests fail. Give Docker at
least 6 GB of memory, because the SQL Server container is large.

## Build and test

Clone the repository and run the build.

```bash
git clone https://github.com/AlterNayte/queuebox.git
cd queuebox
./gradlew build
```

Run the full verification, which includes every test and every coverage gate.

```bash
./gradlew check
```

Produce the aggregated coverage report.

```bash
./gradlew jacocoAggregatedReport
```

The report is written to `build/reports/jacoco/aggregated/html/index.html`.

`check` enforces three coverage gates: 80 percent aggregate line coverage, 70 percent aggregate
branch coverage, and 60 percent line coverage per module. `TESTING.md` documents the gates and the
excluded classes. A change that drops a value below its gate fails the build.

## Run only the fast tests

The fast tests are the tests that start no container. They live in the `core`, `config`,
`outbox-service` and `inbox-service` modules. Run them with a module list.

```bash
./gradlew :core:test :config:test :outbox-service:test :inbox-service:test
```

This command needs no Docker daemon. Use it for a quick loop while you write code.

The build has no wiring that excludes a JUnit tag, so the tags `@Tag("integration")` and
`@Tag("e2e")` cannot select the fast set from the command line today. The module list above is the
command that works. Run `./gradlew check` with Docker before you open a pull request.

## Code style

The build enforces the style. Run the two checks.

```bash
./gradlew ktlintCheck detekt
```

`ktlintCheck` reports every formatting error. `detekt` reports every static analysis error. Both
run as part of `check`, so a style error fails the build.

Fix most findings automatically.

```bash
./gradlew ktlintFormat
```

`ktlintFormat` does not fix a detekt finding. Correct such a finding by hand.

`config/detekt/detekt.yml` holds the rule set. One `config/detekt/baseline-<module>.xml` file per
module holds the findings that the codebase carried before the gate arrived. Every entry is
structural, for example a long method or a magic number. Never add a new entry to the baseline. Correct
the finding instead. To remove an entry, fix the code and delete the line.

Further rules.

1. Follow the official Kotlin coding conventions. `.editorconfig` holds the settings that ktlint
   reads.
2. Write a public name in English, and give it one meaning.
3. Add no new `println`. Use the SLF4J logger. See finding F-046 in `hardening-doc.md`.
4. Log no credential and no full request body. Redact the value first.
5. Add a test for every behaviour change. Write the test before the code where that is possible.

## Dependencies

Every version lives in `gradle/libs.versions.toml`. Never write a
`group:artifact:version` string in a `build.gradle.kts` file. Add the coordinate to the catalog
and reference it, for example `implementation(libs.hikaricp)`.

The build verifies every downloaded artifact against a checksum.
`gradle/verification-metadata.xml` holds one SHA-256 entry per artifact. A build that downloads an
artifact with a different checksum fails, and the message names the artifact.

A version change adds a new artifact, so the metadata needs a new entry. Regenerate it.

```bash
./gradlew --write-verification-metadata sha256 --refresh-dependencies clean build
./gradlew --write-verification-metadata sha256 --no-configuration-cache cyclonedxBom
```

Both commands are needed. `cyclonedxBom` resolves a configuration that `build` never resolves,
and that resolution reads a `.pom` file that `build` never reads.

Review the added entries before you commit them. Each new line is a claim that the artifact is
the one the publisher released.

## Commit messages

The repository follows Conventional Commits. Write the subject line in this form.

```
<type>: <short description>
```

Use one of these types, which are the types the history already uses.

| Type | Use |
|------|-----|
| `feat` | A new capability. |
| `fix` | A correction of a defect. |
| `docs` | A documentation change only. |
| `chore` | Build, tooling, or housekeeping. |

Rules for the subject line.

1. Keep it to 72 characters or fewer.
2. Start the description with a lowercase letter.
3. Use the imperative mood. Write `add the retry gauge`, not `added the retry gauge`.
4. Name the finding identifier when the commit closes a finding, for example
   `feat: F-046 — a logging framework replaces every println`.

Put the detail in the body. Separate the body from the subject with one blank line.

## Branch strategy

`main` is the only long-lived branch. Every commit on `main` must build green.

1. Create a branch from `main` for your work. Name it `feature/<short-name>` for a new capability
   or `fix/<short-name>` for a correction. The history uses the `feature/` prefix.
2. Keep the branch small. One branch closes one finding or one feature.
3. Rebase the branch on `main` before you open the pull request.
4. Open a pull request against `main`. Complete the pull request template.
5. A maintainer merges the pull request after the checks pass and the review is approved.
6. Delete the branch after the merge.

A release comes from a tag on `main`. See [docs/development/releasing.md](docs/development/releasing.md).

## Add a database migration

`docs/development/migrations.md` holds the migration policy. Read it before you write a file. It is
the authority on the file layout, the naming, and the rules that a file must obey.

The short path is this.

1. Read `docs/development/migrations.md`.
2. Add one file to `postgres/src/main/resources/db/postgresql`, with the next version number.
3. Add a file with the same version number to `sqlserver/src/main/resources/db/sqlserver`. Add it
   even when the change applies to one database only, and write a comment that says so.
4. Run `./gradlew :postgres:test :sqlserver:test` with Docker. `PostgresMigratorTest` and
   `SqlServerMigratorTest` replay every file against a schema that already exists.
5. Update the version table in `docs/development/migrations.md`.

Never edit a migration file that a release already carries. Flyway records a checksum, so an edit
breaks every existing deployment. Write a new version instead.

## Pull request checklist

- [ ] `./gradlew check` passes with Docker running.
- [ ] `./gradlew ktlintCheck detekt` passes.
- [ ] A test covers the change.
- [ ] The documentation is updated.
- [ ] `CHANGELOG.md` carries an entry under `Unreleased`.
- [ ] The commit messages follow the convention above.
