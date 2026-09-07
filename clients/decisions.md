# The decisions that every client library copies

The C# library was built first, as section 4 of the work order requires. This document names
every decision that came out of that build. TypeScript, Python and Go copy each one. A library
that departs from one of these states why, in its own README.

## 1. The handler owns the transaction, and the library owns its outcome

The handler receives the live transaction. It must not commit and must not roll back. The
library runs the completion inside that transaction, and it decides the outcome:

- the completion affects one row: commit,
- the completion affects zero rows: roll back, report the lost claim, report no success.

A language whose transaction API cannot pass an open transaction to a callback must pass the
connection that the transaction runs on, and it must still own the commit.

## 2. The claim token never reaches the handler

The library keeps the token. A handler that could reach it could complete a message out of
band, which is the failure the pull path exists to prevent.

## 3. The renewal runs on its own connection

A renewal inside the handler's transaction commits with that transaction, so it renews nothing
and a rollback undoes it. The renewal therefore opens a connection of its own. Every library
needs a connection SOURCE, never a single connection.

## 4. A lost renewal cancels the handler

A renewal that affects zero rows means another worker owns the message. The library sets the
loss, cancels the handler through the language's own cancellation, completes nothing, and
reports. It does not run the retry statement either: the message already belongs to somebody.

## 5. A shutdown abandons, it does not fail

A handler that a shutdown cancels is ABANDONED. The library completes nothing, and it spends no
attempt on the message. The lease expires and another worker takes it.

This one was found by a test, not by design. Running a shutdown through the retry policy looks
harmless, and it is not: every deploy would then spend one attempt of every in-flight message,
and a slow handler would reach the dead letter after enough deploys.

## 6. Every identifier is quoted, and checked

The table and column mapping comes from configuration, and configuration is not a trusted SQL
fragment. Quote every identifier in the dialect's own way, and reject any name that is not a
plain identifier, before the name reaches the database.

## 7. The parameter style follows the driver, not the contract file

`examples/pull/sql` writes `:name`. Bind the parameters in whatever style both drivers of the
language accept, and prove the rendered statement in a unit test. In C# that is `@name`.

## 8. The core package depends on no driver

The library talks to the language's database ABSTRACTION, and the application brings the driver
it already has. One package then serves both dialects. Where the two drivers of a language do
not offer the same abstraction, the library takes a one-method connection source and ships an
adapter for each. In C# this was necessary: Npgsql ships a `DbDataSource` and
`Microsoft.Data.SqlClient` does not.

## 9. The error text passes through the QueueBox redaction

Port `ErrorSanitizer` and `CredentialMasking`, do not invent a new one. The text reaches the
`last_error` column and the caller's log, and a connection string carries a password. Truncate
at two thousand characters, as QueueBox does.

## 10. The tests apply the repository's own migrations

The contract tests read the migration files of `postgres/` and `sqlserver/` and apply them.
A library that keeps its own copy of the schema drifts from QueueBox, silently, one release
later.

## 11. The published package is tested, not only the source

The build packs the package, installs it from a local feed, and builds the README example
against it. An example that the README shows and the package cannot run is a defect.

## 12. What the C# build had to change in the work order

- Section 6 shows `new InboxWorker(dataSource, ...)`. That signature cannot serve both C#
  drivers. See decision 8.
- The section 6 C# example does not compile: `transaction.Connection` is the abstract type, so
  the `AddWithValue` of a driver is not on it. The library therefore ships two small helpers,
  `CreateCommand` and `WithParameter`, and the README uses them.
- Section 10.1 assumes a repository per language. The maintainer chose this repository.
