# QueueBox

[![ci](https://github.com/alternayte/queuebox/actions/workflows/ci.yml/badge.svg)](https://github.com/alternayte/queuebox/actions/workflows/ci.yml)
[![security](https://github.com/alternayte/queuebox/actions/workflows/security.yml/badge.svg)](https://github.com/alternayte/queuebox/actions/workflows/security.yml)
[![release](https://img.shields.io/github/v/tag/alternayte/queuebox?label=release&sort=semver)](https://github.com/alternayte/queuebox/releases)
[![license](https://img.shields.io/github/license/alternayte/queuebox)](LICENSE)
[![coverage](https://img.shields.io/badge/coverage-80%25%20line%2C%2070%25%20branch-informational)](TESTING.md)

QueueBox implements the transactional outbox and the idempotent inbox for you. Your application
writes a row and reads a row. QueueBox handles the delivery, the retries, the deduplication, and
the cleanup. It never interprets your payload.

## Why QueueBox?

A distributed system loses webhooks, receives the same event twice, and fails a delivery with no
record of it. Every team writes the same outbox table, the same retry loop and the same
deduplication check again. QueueBox is that code, written once.

- **It deduplicates an incoming message** on an idempotency key. The same webhook twice stores one
  row.
- **It delivers with retries** and moves an exhausted message to a dead-letter state.
- **It transforms a payload** with a JSONata expression, at ingestion or at delivery.
- **It routes a message** to a destination on a topic pattern.
- **It deletes old rows** on a retention policy you configure.

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

The database credentials come from [.env.example](.env.example), which the Compose file names in
its `env_file` block. `--env-file .env.example` is therefore optional above, and the command works
without it. Copy `.env.example` to your own file and point `env_file` at it for a real
deployment.

[Deliver your first message](https://queuebox-docs.pages.dev/tutorials/first-outbox-message/) holds the full walkthrough, and
[Write outbox rows](https://queuebox-docs.pages.dev/how-to/write-outbox-rows/) holds the contract your application writes against.

## Guarantees

An infrastructure component must state what it promises. QueueBox promises this, and a test proves
each sentence.

**Delivery is at-least-once.** A destination can receive the same message more than once, so a
destination must be idempotent. QueueBox sends the message identifier in the `X-Message-Id` header
so that a receiver can deduplicate.
Proved by `HttpPublisherTest` and `E2EOutboxFlowTest`.

**Ordering holds for one key, at any concurrency.** The outbox delivers the rows that share a
non-empty `key` in insert order, one row of a key at a time. A row that waits for a retry holds back
the later rows of its key, and a dead row releases its key. Rows with an empty `key` and rows of
different keys have no order. See [ordering](https://queuebox-docs.pages.dev/concepts/ordering/#order-and-the-key).
Proved by `OrderingGuaranteeTest.the outbox delivers the rows of one key in insert order at any
concurrency`, `OutboxKeyOrderTest.concurrent claimers deliver each key in insert order` and
`SqlServerOutboxKeyOrderTest.concurrent claimers deliver each key in insert order`.

**A crash can produce a duplicate delivery.** A claimed message that no worker completes returns to
`pending` after the claim timeout. A replacement worker then delivers that message. If the process
died after the destination accepted the message, the second delivery is a duplicate.
Proved by `ReclaimStaleTest.reclaimStale returns a stale outbox claim to pending and keeps the
attempt count` for the reclaim, and by `E2ECrashRecoveryTest.should deliver a message that a
crashed replica left in processing` for the delivery that follows it. The duplicate is the
consequence of that second delivery.

**The inbox deduplicates on `(source, idempotency_key)`.** The deduplication window is the
retention period, because retention deletes the first row. A retention period shorter than the
retry window of the source therefore reopens duplicates. Set retention longer than that window. No
test proves that reopening, so treat the last two sentences as a design note, not as a tested
promise.
Proved by `E2EInboxFlowTest.should detect duplicate when same webhook sent twice` for the
deduplication, and by `RetentionSemanticsTest.inbox age uses created_at, which is the receipt time`
for the age that retention measures.

**A transform error follows the strategy you configure.** `fail` sends the message to the retry
path. `skip` keeps the original payload and continues. `dead` moves the message to the dead-letter
state at once.
Proved by `TransformPipelineTest.SKIP strategy should use original payload on route transform
error`, `TransformPipelineTest.FAIL strategy should return Error on transform failure` and
`TransformPipelineTest.DEAD strategy should return DeadLetter on transform failure` for the three
outcomes. `OutboxPollerTest.should schedule retry on TransformResult Error` and
`OutboxPollerTest.should mark dead on TransformResult DeadLetter` prove that the poller acts on
each outcome.

## Documentation

The docs live at **[queuebox-docs.pages.dev](https://queuebox-docs.pages.dev)**: tutorials, how-to guides, concepts, the
configuration reference and the operations runbook.

- Coding agents read [`/llms.txt`](https://queuebox-docs.pages.dev/llms.txt) or [`/llms-full.txt`](https://queuebox-docs.pages.dev/llms-full.txt), and
  every page is also Markdown at its URL plus `.md`.
- The QueueBox agent skill lives in [skills/queuebox](skills/queuebox/SKILL.md). Install it with
  `npx skills add alternayte/queuebox`, or in Claude Code with
  `/plugin marketplace add alternayte/queuebox` and `/plugin install queuebox@queuebox`.
  [Use QueueBox with coding agents](https://queuebox-docs.pages.dev/how-to/use-coding-agents/) holds the detail.

| In this repository | What it holds |
|--------------------|---------------|
| [docs/adr/](docs/adr) | The architecture decision records. |
| [docs/development/](docs/development) | Building, migrations and releasing QueueBox itself. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test and submit a change. |
| [TESTING.md](TESTING.md) | The test strategy and the coverage gates. |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each release. |

## Database support

QueueBox runs on PostgreSQL and on SQL Server.
[The deploy page](https://queuebox-docs.pages.dev/operations/deploy/) holds the supported versions, and
[Use custom tables](https://queuebox-docs.pages.dev/how-to/use-custom-tables/) holds the column mapping.

## Roadmap

QueueBox 1.0 does not ship these features. The list is a statement of intent, not a promise of a
date.

| Feature | Target | Note |
|---------|--------|------|
| OpenTelemetry tracing | 1.1 | 1.0 correlates a message with the `X-Correlation-Id` header, the `correlation_id` column, and the `correlationId` field of every log line. See [the runbook](https://queuebox-docs.pages.dev/operations/runbook/). |
| Rate limit per destination | 1.2 | 1.0 rate limits an inbox source. |
| Admin user interface | not scheduled | |
| Kubernetes Helm chart | not scheduled | The Kubernetes section of [the deploy page](https://queuebox-docs.pages.dev/operations/deploy/#kubernetes) covers the same ground. |

A row with a version number is work that a maintainer intends to do. A row that says
"not scheduled" is work that nobody has committed to. Do not plan a deployment around a row of the
second kind.

## Support

QueueBox is maintained today. Report a defect as a GitHub issue. Report a security defect through
[SECURITY.md](SECURITY.md), never as a public issue. There is no commercial support contract and
no response time commitment for an issue.

The coverage badge states the gate that CI enforces, not a measured figure. `check` fails below 80
percent line coverage and 70 percent branch coverage across the build, and below 60 percent line
coverage in any module. [TESTING.md](TESTING.md) holds the detail. The badge becomes a measured
figure when the project adopts a coverage service.

## License

[LICENSE](LICENSE)
