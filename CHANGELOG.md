# Changelog

Every notable change to QueueBox appears in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and QueueBox follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).
[docs/development/releasing.md](docs/development/releasing.md) states the compatibility policy for
the configuration schema and for the database schema.

## [Unreleased]

## [0.1.0] — unreleased

The first release. QueueBox is a transactional outbox and inbox relay between HTTP endpoints,
PostgreSQL or SQL Server, and RabbitMQ. Everything below is new to a user, because no earlier
release exists.

### Added

- **Inbox to outbox relay.** An HTTP or AMQP source writes a message to the inbox table. The relay
  moves the message into the outbox table in one transaction. The outbox poller then delivers it.
- **Message ordering per aggregate.** The inbox claim serialises the messages of one aggregate, so
  a consumer sees them in the order that the producer sent them.
- **Crash recovery.** A claim records `claimed_at`. A restarted instance reclaims a stale claim, so
  no message is lost when a process dies between the claim and the delivery.
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

[Unreleased]: https://github.com/AlterNayte/queuebox/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AlterNayte/queuebox/releases/tag/v0.1.0
