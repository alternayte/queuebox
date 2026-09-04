# QueueBox

[![ci](https://github.com/alternayte/queuebox/actions/workflows/ci.yml/badge.svg)](https://github.com/alternayte/queuebox/actions/workflows/ci.yml)
[![security](https://github.com/alternayte/queuebox/actions/workflows/security.yml/badge.svg)](https://github.com/alternayte/queuebox/actions/workflows/security.yml)
[![release](https://img.shields.io/github/v/tag/alternayte/queuebox?label=release&sort=semver)](https://github.com/alternayte/queuebox/releases)
[![license](https://img.shields.io/github/license/alternayte/queuebox)](LICENSE)

QueueBox implements the transactional outbox and the idempotent inbox for you. Your application
writes a row and reads a row. QueueBox handles the delivery, the retries, the deduplication, and
the cleanup. It never interprets your payload.

## Quick start

You need Docker. The stack starts PostgreSQL and QueueBox.

```bash
git clone https://github.com/AlterNayte/queuebox.git
cd queuebox
docker compose -f docker-compose.yml --env-file .env.example up -d --build
curl http://localhost:8080/health
```

Send a message to the example inbox source.

```bash
curl -X POST http://localhost:8080/inbox/stripe \
  -H 'Content-Type: application/json' \
  -d '{"id":"evt_1","type":"payment.succeeded"}'
```

The first request answers `202 Accepted`. The same request again answers `200 OK` with
`{"status":"duplicate"}`.

`docker-compose.yml` mounts [examples/queuebox.yml](examples/queuebox.yml) at
`/etc/queuebox/queuebox.yml`. Edit that file and restart the container. You do not rebuild.

[docs/getting-started.md](docs/getting-started.md) holds the full walkthrough, and
[docs/integration.md](docs/integration.md) holds the contract your application writes against.

## Guarantees

An infrastructure component must state what it promises. QueueBox promises this, and a test proves
each sentence.

**Delivery is at-least-once.** A destination can receive the same message more than once, so a
destination must be idempotent. QueueBox sends the message identifier in the `X-Message-Id` header
so that a receiver can deduplicate.
Proved by `HttpPublisherTest` and `E2EOutboxFlowTest`.

**Ordering holds for one aggregate, and only while concurrency is one.** QueueBox claims the
oldest scheduled message first. Concurrency inside a batch removes ordering between messages,
because two workers publish at the same time. Do not rely on ordering across aggregates.
Proved by `OutboxRepositoryConcurrencyTest.claimBatch returns the oldest scheduled messages in
order`.

**A crash can produce a duplicate delivery.** A claimed message that no worker completes returns to
`pending` after the claim timeout. If the process died after the destination accepted the message,
the retry delivers it a second time.
Proved by `ReclaimStaleTest`.

**The inbox deduplicates on `(source, idempotency_key)`, and the window is the retention period.**
A retention period shorter than the retry window of the source reopens duplicates, because the
first row is gone when the retry arrives. Set retention longer than that window.
Proved by `E2EInboxFlowTest.should detect duplicate when same webhook sent twice`.

**A transform error follows the strategy you configure.** `fail` sends the message to the retry
path. `skip` keeps the original payload and continues. `dead` moves the message to the dead-letter
state at once.
Proved by `InboxTransformPipelineTest`.

## Documentation

| Document | What it holds |
|----------|---------------|
| [docs/getting-started.md](docs/getting-started.md) | The full quick start, the HTTP API, and the usage patterns. |
| [docs/integration.md](docs/integration.md) | The outbox insert contract. Read this before you write code. |
| [docs/configuration.md](docs/configuration.md) | The full configuration reference and the database support. |
| [docs/architecture.md](docs/architecture.md) | The module graph, the message lifecycle, and the state diagrams. |
| [docs/message-flow.md](docs/message-flow.md) | The message flow, the inbox relay, aggregate ordering, and the schema. |
| [docs/transforms.md](docs/transforms.md) | The JSONata transforms and the routing key templates. |
| [docs/authentication.md](docs/authentication.md) | The authentication of a destination and of a source. |
| [docs/operations/runbook.md](docs/operations/runbook.md) | The operations runbook. |
| [docs/operations/metrics.md](docs/operations/metrics.md) | Every metric that `/metrics` exposes. |
| [docs/operations/dead-letter.md](docs/operations/dead-letter.md) | How to inspect and replay a dead message. |
| [docs/operations/security.md](docs/operations/security.md) | The deployment hardening notes. |
| [docs/adr/](docs/adr) | The architecture decision records. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test and submit a change. |
| [TESTING.md](TESTING.md) | The test strategy and the coverage gates. |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each release. |

## Database support

QueueBox runs on PostgreSQL and on SQL Server.
[docs/configuration.md](docs/configuration.md) holds the supported versions and the column mapping.

## Roadmap

QueueBox 1.0 does not ship these features. The list is a statement of intent, not a promise of a
date.

| Feature | Target | Note |
|---------|--------|------|
| OpenTelemetry tracing | 1.1 | 1.0 correlates a message with the `X-Correlation-Id` header, the `correlation_id` column, and the `correlationId` field of every log line. See [docs/operations/runbook.md](docs/operations/runbook.md). |
| Message replay from the dead-letter state | 1.1 | 1.0 documents the SQL. See [docs/operations/dead-letter.md](docs/operations/dead-letter.md). |
| Kafka destination | 1.2 | |
| Rate limit per destination | 1.2 | 1.0 rate limits an inbox source. |
| Admin user interface | not scheduled | |
| Kubernetes Helm chart | not scheduled | The deployment examples in [docs/operations/security.md](docs/operations/security.md) cover the same ground. |

A row with a version number is work that a maintainer intends to do. A row that says
"not scheduled" is work that nobody has committed to. Do not plan a deployment around a row of the
second kind.

## Support

QueueBox is maintained today. Report a defect as a GitHub issue. Report a security defect through
[SECURITY.md](SECURITY.md), never as a public issue. There is no commercial support contract and no
response time commitment for an issue.

## License

[LICENSE](LICENSE)
