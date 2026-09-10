# Changelog

Every notable change to QueueBox appears in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and QueueBox follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).
[docs/development/releasing.md](docs/development/releasing.md) states the compatibility policy for
the configuration schema and for the database schema.

## [Unreleased]

## [0.2.0] — 2026-09-10

Four tags publish together at this release: `v0.2.0`, which ships the server image alone, and
`csharp-v0.2.0`, `typescript-v0.2.0` and `clients/go/v0.2.0`, which each ship one pull client
library. A tag ships only the artifact it names. The entries below say which tag carries each
change, so an operator upgrading the image, or a consumer upgrading one client library, can tell
what they actually get.

This is also the first entry in this file for Kafka, for NATS, and for the three pull client
libraries, none of which the `0.1.0` entry named. The client libraries already shipped once each,
as `csharp-v0.1.0` and `csharp-v0.1.1`, as `typescript-v0.1.0`, and as `clients/go/v0.1.0` and
`clients/go/v0.1.1`, each carrying an initial pull worker for PostgreSQL and SQL Server. This
release is the first to record any of that here.

### `v0.2.0` — the server image

#### Added

- **A Kafka inbox source and outbox destination.** Kafka has no per-message acknowledgement, only
  an offset, so the consumer processes one partition in offset order and commits only the run that
  the inbox already stored. A body that is not JSON is stored dead and its offset still moves.
- **A NATS JetStream inbox source and outbox destination.** The source is JetStream only, because
  core NATS delivers a message once, to whoever is listening, and cannot acknowledge anything. The
  consumer is durable and pull based, and it acknowledges one message at a time, only after the
  inbox row commits.
- **A per-row destination address.** A RabbitMQ destination can set its own `exchange` as a
  template, or read the exchange from a row column through `exchangeFrom`. The same choice exists
  for the Kafka `topic` (`topicFrom`) and the NATS subject (`subjectFrom`). The column a `*From`
  setting names must be one of exactly three permitted names: `aggregate_type`, `topic`, or `key`;
  any other name fails the startup. A service with many aggregate types no longer needs one
  destination and one route per type. A rendered address that is empty, or that the broker refuses,
  fails the row through the normal retry path; QueueBox never guesses a name and never falls back
  to a default.
- **An `aggregate_type` field to render an address from.** A row can carry `aggregate_type`, and a
  destination template can read it, in the same way Debezium reads `aggregatetype`.
- **Startup validation of every address template.** QueueBox now checks the exchange template, the
  topic template and the subject template of every destination at startup, and fails fast with the
  name of the unknown field and the name of the destination, instead of failing on the first
  message.
- **A RabbitMQ destination can set its own `routingKeyTemplate`.** The router renders it against the
  row, and the publisher uses the result when the matched route sets no `routingKeyTemplate` of its
  own.
- **A documented outbox insert.** `docs/integration.md` now shows a worked insert for each database,
  inside the application transaction, with `headers` and `aggregate_type` populated, together with
  the Entity Framework Core mapping for both columns.
- **A queue-not-found error that names its own fix.** A missing RabbitMQ source queue now states
  that the queue does not exist, that QueueBox does not declare a source queue by default, and that
  `declareQueue: true` changes that. A new per-source `declareQueue` setting, default `false`,
  declares a durable queue with the configured name before QueueBox consumes from it.
- **Two lag metrics, in seconds.** `queuebox_outbox_oldest_pending_age_seconds` and
  `queuebox_inbox_oldest_pending_age_seconds` report the age of the oldest row in state `pending`
  for the outbox table and the inbox table, and zero when no row is pending. A row count alone
  cannot tell an operator whether the relay is busy or has stopped. Each gauge refreshes at most
  once per interval: `outbox.pendingGaugeIntervalMs` and `inbox.relay.pendingGaugeIntervalMs`,
  both 5000 by default, so a metrics scrape runs no extra database query.
- **`POST /admin/replay`.** Behind the existing admin guard, the endpoint moves a selected set of
  `sent` or `dead` rows back to `pending`, selected by a time range, by source, by destination, or
  by an explicit list of identifiers. A request with no filter answers 400, because a replay of
  everything must never be an accident. A row in `pending` or `processing` never moves, because the
  relay owns it. The response reports the count of rows moved.
- **A per-source `attributeHeaders` block.** A source can map the three inbox attribute header
  names, `idempotencyKey`, `aggregateId` and `eventType`, to the header names its own producer
  sends. Each name defaults to the value QueueBox has always read, so no existing deployment
  changes. The setting replaces a header name inside the existing fallback chain and keeps the
  chain order. RabbitMQ, Kafka and NATS all read this setting.
- **The ordering guarantee, stated in `docs/delivery-semantics.md`.** QueueBox delivers at least
  once. In push mode, one aggregate holds at most one message in flight, so the relay preserves the
  order of that aggregate. In pull mode the same rule now holds, and it holds across every worker
  instance. QueueBox preserves no order between two different aggregates. A poller delivers in claim
  order, not in commit order, so a reader that needs commit order must not rely on the row
  identifier.

#### Fixed

- **The RabbitMQ consumer no longer requeues a body that never parses.** A body that is not JSON now
  produces one row in state `dead`, in one transaction, with the raw body and the reason, and one
  acknowledgement. Before this fix the consumer requeued the same body forever at broker speed, and
  an operator had to stop the container and purge the queue by hand. A genuine storage failure still
  requeues, because that failure is transient and a body that is not JSON is not.

#### Breaking changes

- **A route-level `routingKeyTemplate` no longer selects the NATS subject.** Use the destination's
  `subject` template or `subjectFrom` instead.

#### Security

- The Netty version floor is raised to 4.2.17.Final, which closes CVE-2026-75595.
- The redaction of a driver error and of a masked credential now runs in time linear to the length
  of the text. The previous pattern was quadratic, so a destination or a driver that returned a
  long error text could stall the caller. The behaviour is unchanged for every real credential and
  every registered URI scheme.

#### Known limitations

- **SQL Server pull claim throughput has a per-source ceiling.** The claim serializes per source
  through an application lock, so one source reaches roughly 110 to 140 claims per second, and
  adding more workers to that source does not raise the ceiling. Scale by adding sources instead of
  workers. PostgreSQL has no such constraint, because its claim does not serialize through an
  application lock.
- **SQL Server stores `created_at` as the local wall clock of the writing host, not as a UTC
  instant**, because the column is `DATETIME2` and the driver converts it through the JVM default
  calendar. This release adds three operator-visible features that read that column: the two age
  gauges and the `POST /admin/replay` time-range filter. Claim order and retention age already
  depended on it. Each is wrong by the host's UTC offset when application instances, or the
  database itself, do not share one time zone. Run every QueueBox instance for one SQL Server
  database in the same time zone, and prefer UTC for all of them.

### Migrations

Two migrations ship with this release, for both PostgreSQL and SQL Server.

- **`V9__add_aggregate_type.sql`** adds a nullable `aggregate_type` column to the outbox table. It
  is additive. An existing writer that never sets the column keeps working.
- **`V8__add_pull_claim_indexes.sql`** adds two indexes that the pull claim needs. On both engines
  the migration uses a plain `CREATE INDEX`, which locks out inserts to the inbox table for the
  duration of the build. **Applying this migration to a populated database needs a maintenance
  window.** An operator who cannot take one must apply the index online instead:
  `CREATE INDEX CONCURRENTLY` on PostgreSQL, run outside a transaction block; on SQL Server,
  `CREATE INDEX ... WITH (ONLINE = ON)`, available on Enterprise Edition and on Azure SQL. Both
  migrations create filtered indexes, which on SQL Server need `SET QUOTED_IDENTIFIER ON` at
  create time. Flyway's own driver sets this on by default, but an operator who applies `V8`
  by hand on SQL Server must set it explicitly first. See `docs/development/migrations.md`.

### `csharp-v0.2.0`, `typescript-v0.2.0`, `clients/go/v0.2.0` — the pull client libraries

These three tags publish together, each at 0.2.0, and each carries the same three changes.

#### Added

- **`QueueBox.Inbox.DependencyInjection`, a new NuGet package, published for the first time
  alongside `csharp-v0.2.0`.** It ships `AddQueueBoxInbox`, which registers the options, the
  connection source, and exactly one hosted service per named worker. A second registration under
  the same name throws instead of silently replacing the first worker. It also ships an Entity
  Framework Core helper that builds a database context on the connection and the transaction of the
  message, so the application write and the completion commit together or neither does. The core
  package, `QueueBox.Inbox`, keeps its single dependency on
  `Microsoft.Extensions.Logging.Abstractions`, which is why these helpers ship in a second package
  rather than in the core one. Both packages publish at the same version, and
  `QueueBox.Inbox.DependencyInjection` depends on `QueueBox.Inbox` pinned to that exact version, so
  the two can never drift apart.
- **The pull claim now reserves one in-flight message per aggregate**, in all three client
  libraries, on both PostgreSQL and SQL Server. Before this fix, several handlers of one aggregate
  ran at the same time in an arbitrary order, and a handler that read a row and then wrote it failed
  with a duplicate key error whenever it met a sibling message.

#### Breaking changes

- **The pull client concurrency default is now one**, in all three client libraries. It was the
  batch size, so as many handlers ran at one time as the batch size configured, and nothing in the
  API said so. A handler that read a row and then wrote it failed with a duplicate key error when it
  met a sibling message. To restore the old behaviour, set `MaxConcurrency` (C# and Go) or
  `maxConcurrency` (TypeScript) to the batch size.
- **The SQL Server pull claim's lock timeout is now ten seconds**, down from a longer wait that
  competed with driver defaults. Set every driver request timeout or command timeout to at least 30
  seconds, so the server always raises `Msg 51000` before the driver itself gives up. A driver
  timeout that fires first hides the retry signal and can leave the per-source lock held until the
  connection resets.
- **`Msg 51000` from a pull claim is now a transient error, and a client must retry it rather than
  treat it as a data error.** The message means that `sp_getapplock` could not take the per-source
  lock in time, because another worker of the same source holds it, not that the claimed row is
  invalid. A client that already retries a transient failure needs no code change; a client that
  distinguishes error codes must add this one to its retry list.

## [0.1.0] — 2026-09-06

The first release. QueueBox is a transactional outbox and inbox relay between HTTP endpoints,
PostgreSQL or SQL Server, and RabbitMQ. Everything below is new to a user, because no earlier
release exists.

### Added

- **Inbox to outbox relay.** An HTTP or AMQP source writes a message to the inbox table. The relay
  moves the message into the outbox table in one transaction. The outbox poller then delivers it.
- **Message ordering per aggregate.** The inbox claim serialises the messages of one aggregate, so
  a consumer sees them in the order that the producer sent them.
- **Crash recovery.** A claim records an opaque token and a lease. A restarted instance reclaims an
  expired claim, so no message is lost when a process dies between the claim and the delivery.
- **Push and pull sources.** A source declares `consumption: push` or `consumption: pull`, and the
  value is stored on the inbox row when the message arrives. The relay claims push rows only, so a
  pull source needs no topic, no route and no destination. `push` is the default.
  [`examples/pull`](examples/pull) publishes the claim, renewal, completion, retry and dead letter
  SQL of both databases, and the tests execute those files. For a pull row `processed` means the
  application finished the work; for a push row it means QueueBox forwarded the row into the
  outbox. See [docs/delivery-semantics.md](docs/delivery-semantics.md).
- **A claim fence that a clock cannot move.** Every terminal write of both tables needs the claim
  token that the claim returned and a lease that has not expired. The comparison happens inside
  SQL against the database clock, so no driver time zone can change an ownership decision. A
  worker renews the lease while it works, and losing ownership cancels the work.
- **Change data capture, optional and off by default.** `outbox.capture.mode` accepts
  `postgres-logical` or `sqlserver-cdc`. An embedded connector reads the database log and wakes
  delivery when an outbox row is inserted, which shortens the delay between the commit and the
  delivery. Nothing external is needed: no Kafka, no Kafka Connect, no Debezium Server. Capture
  never delivers anything, so delivery continues while capture is down or disabled. Exactly one
  process may own a capture identity, and recovery from lost or changed capture state is an
  explicit operator decision. See [docs/capture.md](docs/capture.md) and
  [`examples/cdc`](examples/cdc).
- **Retry with a recorded reason.** A failed delivery is rescheduled with a backoff. The outbox row
  carries the sanitised error in `last_error`, so an operator can see why the delivery failed.
- **Dead letter handling.** A message that exhausts its retries moves to the dead state.
  `docs/operations/dead-letter.md` documents how to list and requeue such a message.
- **Retention.** A background service deletes processed and dead rows in bounded batches, so the
  delete does not lock the table for a long time.
- **Database migrations at startup.** Flyway applies the bundled migration set for PostgreSQL or
  for SQL Server. `docs/development/migrations.md` holds the policy.
- **Custom table and column names.** A deployment can map every table and every column to an
  existing schema. QueueBox quotes each identifier and validates it.
- **Inbox authentication.** A source can require an HMAC signature over the timestamp and the body,
  a bearer credential, or a static header. Every credential comparison runs in constant time.
- **Request limits.** Each route caps the request body size and applies a per-source rate limit
  that answers 429 with `Retry-After`.
- **Outbound URL validation.** QueueBox validates every configured destination URL, follows no
  redirect, and rejects a dot segment in the path.
- **Credential masking.** A `Secret` type carries every credential. A log line, an error body, a
  metric and a configuration dump all show a masked value.
- **A `file:` credential reference.** A credential field can read its value from a file, which
  suits a Kubernetes secret mount.
- **Structured logging.** SLF4J and Logback replace every `println`. The mapped diagnostic context
  carries the message-scoped fields.
- **Correlation identifiers.** The inbox accepts or generates `X-Correlation-Id`, stores it, and
  the relay forwards it to the destination.
- **Health endpoints.** `/health/live` does no input or output. `/health/ready` reports the state
  of the database, the broker, and each background service.
- **Prometheus metrics.** Counters for the accepted, delivered, retried and dead messages, a queue
  depth gauge, and an information gauge that carries the build version.
- **A separate management port.** `server.managementPort` moves the health, metrics and admin
  endpoints to a second server, so they need not face the internet.
- **Admin endpoints.** The admin endpoints are disabled by default. When enabled they require
  authentication and clamp the timeout and the payload size.
- **Graceful shutdown.** The instance drains the in-flight work, stops the HTTP server, stops the
  services, then closes the resources, inside a bounded timeout.
- **A start that waits for the database.** The start retries the database connection with a
  backoff, so a container does not fail because the database is still coming up.
- **Startup validation.** QueueBox compiles every configured transform and validates the routing
  configuration at startup, so a broken configuration fails fast with a named error.
- **A JSONata transform pipeline.** A route can transform a message body before delivery.
- **Java 21 support.** The build and both container images target the Java 21 long term support
  release.
- **A software bill of materials.** The build produces a CycloneDX bill of materials, and the
  security workflow scans it. Both base images are pinned by digest.
- **Operator documentation.** `docs/operations/` holds a runbook, a security guide and a dead
  letter guide. `TESTING.md` documents the coverage gates.
- **Community documents.** `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, this changelog,
  the issue templates, the pull request template, and `CODEOWNERS`.
- **Continuous integration.** A workflow builds the project, runs the tests against a matrix of
  database versions, runs the style checks, and scans the container image. A tagged release
  publishes a multi-architecture image to the GitHub container registry.
- **Code style enforcement.** ktlint and detekt run as part of `./gradlew check`.

### Fixed

- **A scheduled retry on SQL Server no longer waits for the reconciliation interval.** The two
  deadline columns do not share one clock: `scheduled_at` is written through the driver from the
  application clock, and `lease_expires_at` is written by `SYSUTCDATETIME()`. The wake query
  compared one against the other, which put the wake off by the offset of the application time
  zone on any host that does not run in UTC. Each deadline is now measured against its own clock.

### Breaking changes

- **The default `topic` of a RabbitMQ source is now `{{ source }}`.** The default was
  `{{ eventType }}`. A RabbitMQ source reads the event type from the AMQP header `x-event-type`
  only, and that header was documented nowhere. A publisher that did not set it produced an empty
  topic, and the relay marked every such message dead after the acknowledgement to the broker. The
  message was destroyed. The new default renders the source name, which every message carries, so
  no default can destroy a message. A RabbitMQ source can now also set `eventTypePath`, which
  reads the event type from the message body, like an HTTP source. QueueBox refuses the start when
  the topic template of a RabbitMQ source uses `{{ eventType }}` and neither `eventTypePath` nor
  `eventTypeFromHeader` is set. A deployment that relied on the old default must set
  `topic: "{{ eventType }}"` and one of those two fields.

- **The inbox accepts a new message with 202.** The `POST /inbox/<source>` route returned 200 for a
  message that it stored. It now returns 202 Accepted, because the message is stored but not yet
  forwarded. A duplicate message still returns 200. There is no compatibility flag. A client that
  tests for the exact code 200 must accept 202 as well.
  `docs/adr/0002-inbox-accept-returns-202.md` records the decision.

- **`retention.inbox.policy: COUNT` fails the startup.** The value was accepted and then did
  nothing: the service logged a warning and deleted no row, so the inbox table grew without bound
  while the startup looked clean. QueueBox now rejects the value at startup and names the two that
  work, `AGE` and `DISABLED`. A deployment that set `COUNT` was never getting the cleanup it asked
  for, so the loud failure reports a defect that already existed.

- **The V6 migration needs every old worker stopped first.** `V6__add_consumption_and_leases.sql`
  adds the consumption, claim token and lease columns, and `V7__capture_state.sql` adds the capture
  registry. Both are additive and rewrite no data. The old worker fences a claim on a timestamp and
  the new worker fences it on a token and a lease, and the two must never run at the same time,
  because an old worker can complete a row that a new worker owns. Stop every worker, apply both
  files, then start the new workers. Existing inbox rows migrate as `push`, which keeps the
  previous behaviour. A custom schema must add and map the new columns by hand. See
  [docs/development/migrations.md](docs/development/migrations.md).

- **`outbox.maxAttempts` now reaches the message.** The value was validated and never applied.
  Every message that QueueBox created took the schema default of 5, whatever the configuration
  said. QueueBox now stamps the configured value on every row it creates. A deployment that set a
  value other than 5 sees its retry ceiling change to the value it configured. The `max_attempts`
  column still wins, so an adopter can still override the ceiling for one message.

### Security

- The inbox HMAC check covers the timestamp and the body together, which blocks a replay of the
  signature against a different body.
- A failed HTTP delivery reads a bounded part of the error body, then redacts it, so a destination
  cannot push a credential into a log.
- The admin surface is off by default.

[Unreleased]: https://github.com/AlterNayte/queuebox/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/AlterNayte/queuebox/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/AlterNayte/queuebox/releases/tag/v0.1.0
