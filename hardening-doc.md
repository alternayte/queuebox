# QueueBox Hardening Document

**Version:** 1.0
**Date:** 2026-09-03
**Scope:** Full code and documentation review of the `queuebox` repository at commit `448944e`.
**Goal:** Bring the repository to the quality level of a mainstream open source project, for
example Apollo Federation, Temporal, or Debezium.

---

## 0. How to use this document

This document is the authoritative work order. It replaces a Software Design Document (SDD) for
the hardening work. Treat sections 3 to 12 as immutable requirements.

**Rules for the implementing agent:**

1. Do not redesign the product. Do not add features that are not listed here.
2. Work through the phases in order. Phase N+1 must not start before the exit condition of
   Phase N is met with evidence.
3. Every finding has an ID, for example `F-012`. Reference the ID in the commit message.
4. Every finding has a **Definition of Done (DoD)**. The DoD is a command or a test that
   produces evidence. A finding is not done until the evidence exists.
5. Write the test first. Confirm the test fails. Then write the fix. Then confirm the test passes.
6. After each phase, update `docs/build/STATUS.md` with the durable state.
7. Do not mark a finding as done because the code "looks correct". Run the command.
8. Section 2A records the product decisions the maintainer has already made. They are closed.

**Verification baseline used for this review:**

- `./gradlew compileKotlin compileTestKotlin` — passes at commit `448944e`.
- Finding F-001 was reproduced against a live PostgreSQL 16 container. See F-001 for the
  reproduction transcript.
- The remaining findings come from static review of all `src/main` sources, all build files,
  `README.md`, `TESTING.md`, `Dockerfile`, and both Compose files. The test suite was not
  executed, so no finding below claims that a test currently fails.

---

## 1. Executive summary

QueueBox has a clean module layout, a good test count, a version catalog, JaCoCo gates, and a
detailed README. The foundations are sound.

It is not ready to be presented as a production-grade open source project. Three classes of
problem block that:

1. **Advertised behaviour that does not exist in the running application.** The RabbitMQ
   publisher is never registered. The inbox relay does not exist, so stored inbox messages are
   never processed. Route-level routing keys are computed and then discarded. Aggregate ordering
   is documented as a product feature but no component calls the code that implements it.
2. **Correctness and durability defects in the message path.** Messages stuck in `processing`
   are never recovered. The inbox claim query does not lock rows, so two instances claim the same
   messages. Retention "batching" is not batched. Inbox age retention deletes nothing.
3. **Missing open source governance.** No LICENSE file although the README declares MIT. No CI.
   No SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md, CHANGELOG.md, issue templates, or
   CODEOWNERS. No release or versioning process. No published artifact. Agent tooling
   (`.taskmaster`, `.cursor`, `.claude`, `.mcp.json`) is committed, and `.env.example` describes
   AI provider keys instead of QueueBox configuration.

Counts: 13 blocker findings, 23 major findings, 21 minor findings, 14 governance findings.

---

## 2. Severity definitions

| Severity | Meaning |
|----------|---------|
| **Blocker** | Data loss, duplicate delivery, silent no-op of an advertised feature, or a security hole. Must be fixed before any public release. |
| **Major** | Operational risk, incorrect documentation, or a defect that a serious adopter will hit in the first week. |
| **Minor** | Quality, consistency, or polish. Fix before the 1.0 tag. |
| **Governance** | Repository hygiene expected of a mainstream open source project. |

---

## 2A. Settled product decisions

The maintainer made these decisions on 2026-09-03. They are authoritative and closed. The
implementing agent must not reopen them, must not offer alternatives, and must not ask about them.

| # | Finding | Decision |
|---|---------|----------|
| 1 | F-002 | QueueBox forwards inbox messages onward. The inbox relay moves a stored inbox message into the outbox table, and the existing outbox machinery delivers it. QueueBox runs no business logic on a message, because it does not know the intent of the message. No handler plug-in point in version 1. |
| 2 | F-063 | Container image only. No Maven artifacts, no `maven-publish`, no signing. QueueBox ships as a deployable service, not as a library. |
| 3 | F-069 | The raised coverage thresholds are accepted: 80 percent aggregate line, 70 percent aggregate branch, 60 percent per module. They block merges, and that is intended. Raise them at the end of Phase 2, not earlier. |
| 4 | F-078 | Change the inbox accept response from 200 to 202. There are no public consumers, so no compatibility shim and no configuration flag. |
| 5 | F-065 | Preserve no Task Master planning content. Remove the agent tooling directories and write the Phase 6 documentation from the shipped code. |

**Consequence for the product statement.** QueueBox exists so that each application does not build
the same plumbing again. The outbox pattern and the inbox pattern are well understood and are
tedious to implement correctly: the claim query, the retry schedule, the dead-letter path, the
deduplication index, the cleanup job. Every service that needs them writes them again, and each
copy has its own defects. QueueBox implements them once, as infrastructure beside the application
database.

Use this statement when Phase 6 rewrites the README:

> QueueBox implements the transactional outbox and the idempotent inbox for you. Your application
> writes a row and reads a row. QueueBox handles the delivery, the retries, the deduplication, and
> the cleanup. It never interprets your payload.

Three consequences bind the implementation:

1. **No business logic.** QueueBox does not decide what a message means. Transforms reshape a
   payload; they decide nothing. Do not add a handler plug-in point, a scripting hook, or a
   callback interface.
2. **The database contract is the product surface.** An application integrates by inserting an
   outbox row inside its own transaction, and by reading inbox rows. That contract must be
   documented as carefully as the HTTP API, because it is the part adopters depend on. Phase 6 must
   give the outbox insert its own documented example per supported database, showing the insert
   inside the application transaction.
3. **The guarantees must be stated plainly.** Say at-least-once delivery, say where ordering holds
   and where it does not, and say what happens on a crash. An infrastructure component that is
   vague about its guarantees is not adoptable. Section 11 requires a test behind each stated
   guarantee.

Remove any wording that suggests QueueBox applies business rules or replaces a message broker. It
sits between the application and the broker.

---

## 3. Phase plan

Each phase has an exit condition. The exit condition is a command whose output is the evidence.

| Phase | Title | Findings | Exit condition |
|-------|-------|----------|----------------|
| 1 | Truth in advertising | F-001 to F-012 | All Phase 1 blockers closed. `./gradlew check` passes. Every README claim maps to a passing test. |
| 2 | Durability and correctness | F-013 to F-033 | All major findings closed. New integration tests for crash recovery, concurrency, and retention pass. |
| 3 | Security hardening | F-034 to F-045 | All security findings closed. `/admin` is authenticated. Request size limits enforced. Secrets never printed. |
| 4 | Observability and operations | F-046 to F-057 | Structured logging replaces every `println`. Graceful shutdown proven by test. Runbook published. |
| 5 | Open source governance | F-058 to F-071 | LICENSE, CI, templates, and release process in place. A new contributor can go from clone to green build using only `CONTRIBUTING.md`. |
| 6 | Documentation and polish | F-072 to F-085 | Docs restructured under `docs/`. Every code sample in every document is executed by a test. |

---

## 4. Phase 1 — Truth in advertising (Blockers)

These findings share one shape: the documentation or the configuration schema promises a
behaviour that the running application does not perform.

---

### F-001 — Inbox claim query does not lock rows; concurrent instances claim the same messages

**Severity:** Blocker
**File:** `postgres/src/main/kotlin/InboxRepository.kt:47-104`

`claimPending` builds a query where `FOR UPDATE SKIP LOCKED` is applied to a `SELECT` whose
`FROM` is a common table expression (`candidates`). PostgreSQL applies the locking clause to the
CTE output, not to the base table rows, so no row locks are taken.

**Reproduction (executed during this review against PostgreSQL 16):**

```sql
CREATE TABLE inbox (id int primary key, aggregate_id text, state text,
                    created_at timestamptz default now());
INSERT INTO inbox(id,aggregate_id,state) SELECT g, NULL, 'pending' FROM generate_series(1,5) g;

-- run this in two concurrent sessions
BEGIN;
WITH independent_messages AS (
  SELECT * FROM inbox WHERE aggregate_id IS NULL AND state='pending'),
candidates AS (SELECT * FROM independent_messages)
SELECT id FROM candidates ORDER BY created_at ASC LIMIT 5 FOR UPDATE SKIP LOCKED;
SELECT pg_sleep(3);
COMMIT;
```

Both sessions returned rows 1 to 5. Expected: the second session returns zero rows.

**Impact:** Two QueueBox replicas process every inbox message twice. The inbox pattern's core
guarantee is broken.

**Fix:** Rewrite `claimPending` as a single `UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED)`
statement against the base table, and return the updated rows with `RETURNING`. Shape:

```sql
UPDATE <inbox> AS t
SET <state> = 'processing'
FROM (
    SELECT <id> FROM <inbox>
    WHERE <state> = 'pending'
      AND ( <aggregate_id> IS NULL
            OR <aggregate_id> NOT IN (SELECT DISTINCT <aggregate_id> FROM <inbox>
                                      WHERE <aggregate_id> IS NOT NULL AND <state> = 'processing') )
    ORDER BY <created_at> ASC
    LIMIT ?
    FOR UPDATE SKIP LOCKED
) AS c
WHERE t.<id> = c.<id>
RETURNING t.<id>, t.<source>, ...;
```

The "one message per aggregate" rule must then be applied after the claim, in Kotlin, by keeping
the first message per aggregate and releasing the rest back to `pending` in the same transaction.
An alternative is one advisory lock per aggregate. Choose one, record the choice in a code
comment, and state the resulting guarantee in the documentation.

**DoD:**
- A new test `InboxRepositoryConcurrencyTest` starts two coroutines against one Testcontainers
  PostgreSQL instance, each calling `claimPending(50)` against 100 pending rows, and asserts the
  intersection of the two returned ID sets is empty and the union has size 100.
- The same test exists for SQL Server (`SqlServerInboxRepositoryConcurrencyTest`).
- `./gradlew :postgres:test :sqlserver:test` passes.

---

### F-002 — The inbox has no relay; stored messages are never processed

**Severity:** Blocker
**Files:** `app/src/main/kotlin/App.kt`, `core/src/main/kotlin/repository/InboxRepositoryInterface.kt:23,28`

`claimPending` and `markProcessed` are called only from tests. No component in `app` polls the
inbox table. Every inbox message stays in state `pending` forever. The README documents aggregate
ordering, inbox retention of `processed` rows, and the state value `processed`. None of that can
occur.

**Impact:** The inbox half of the product is a write-only log. Retention with policy `age` on the
inbox deletes nothing, because no row ever reaches `processed`.

**Product decision (made by the maintainer, authoritative):** QueueBox forwards inbox messages
onward. QueueBox is infrastructure. It runs no business logic on a message, because it does not
know the intent of the message. The inbox relay therefore moves a stored inbox message into the
outbox table, and the existing outbox machinery routes, transforms, and delivers it. There is no
pluggable handler and no user code in version 1.

**Fix — implement `InboxRelay` in `inbox-service`:**

1. Model the relay on `OutboxPoller`. It runs one coroutine loop with its own configuration block:
   `inbox.relay.enabled` (default true), `inbox.relay.pollIntervalMs` (default 100),
   `inbox.relay.batchSize` (default 100), and `inbox.relay.claimTimeoutMs` (see F-006).
2. Each cycle: call `claimPending(batchSize)`, convert each `InboxMessage` to an `OutboxMessage`,
   insert it, and call `markProcessed(id)`.
3. The insert into the outbox and the `markProcessed` call must run in one transaction. If the
   transaction fails, the row stays `processing` and the F-006 reclaim returns it to `pending`.
   This gives at-least-once forwarding, which the outbox idempotency key then bounds.
4. Field mapping, fixed and documented:
   - `outbox.topic` comes from a new per-source configuration field
     `sources.<name>.topic`, a template supporting `{{ source }}` and `{{ eventType }}`. The
     default is `{{ eventType }}`, and the relay fails the message to dead when the template
     renders empty.
   - `outbox.key` takes the inbox `aggregate_id`.
   - `outbox.payload` takes the stored inbox payload, which is already transformed at ingestion.
   - `outbox.headers` carries `x-inbox-id`, `x-source`, and `x-idempotency-key`.
5. Add an `InboxRepositoryInterface` method or reuse the outbox repository from the relay. Do not
   add a second database abstraction.
6. Add metrics `queuebox_inbox_messages_total{status="forwarded"}` and
   `queuebox_inbox_relay_errors_total`.
7. Document the whole path in the README: receive, deduplicate, transform, store, forward, route,
   deliver. State that QueueBox never interprets the payload.

Do not add a plug-in point, a scripting hook, or a callback interface. That is out of scope.

**DoD:**
- `E2EInboxRelayTest` posts to an inbox HTTP source, and asserts, in order: the inbox row reaches
  `processed`; a matching outbox row exists with the mapped topic, key, and headers; and the
  message is delivered to the configured destination.
- A test asserts that a failure to insert the outbox row leaves the inbox row recoverable, and
  that the F-006 reclaim returns it to `pending`.
- A test asserts that two relay replicas forwarding 100 messages produce exactly 100 outbox rows.
- The README section "Aggregate Ordering" states exactly what the shipped code does.

---

### F-003 — RabbitMQ destinations are advertised but never wired; every such message is dead-lettered

**Severity:** Blocker
**Files:** `app/src/main/kotlin/App.kt:106-110`, `rabbitmq/src/main/kotlin/RabbitPublisher.kt`

`App.kt` builds `publishers = listOf(httpPublisher)`. `RabbitPublisher` is constructed only in
tests. `OutboxPoller.processMessage` marks a message dead when no publisher supports the
destination (`OutboxPoller.kt:65-72`). Every message routed to a `type: rabbitmq` destination is
therefore silently dead-lettered, while the README documents RabbitMQ destinations, exchanges,
exchange types, and routing key templates.

**Fix:** Construct `RabbitPublisher` in `App.kt`, add it to `publishers`, and close it in the
shutdown hook. Add a startup validation that fails fast when a configured destination has no
publisher that supports it.

**DoD:**
- `E2EOutboxFlowTest` gains a case that inserts an outbox row routed to a RabbitMQ destination and
  asserts the message arrives on the exchange and the row reaches `sent`.
- A startup test asserts that a configuration whose destination type has no registered publisher
  fails at startup with a named error, rather than dead-lettering at runtime.

---

### F-004 — Route-level routing keys are computed and then discarded

**Severity:** Blocker
**Files:** `outbox-service/src/main/kotlin/MessageRouter.kt:41-64`,
`rabbitmq/src/main/kotlin/RabbitPublisher.kt:34-36`

`MessageRouter.route` returns `RoutingResult.routingKey`, rendered from
`RouteConfig.routingKeyTemplate` with payload field substitution. Nothing reads that field.
`RabbitPublisher` renders its own key from `Destination.RabbitMQ.routingKeyTemplate`, which
supports only `{{ topic }}`. The README documents `routingKeyTemplate` and
`routingKeyMissingFieldDefault` on routes with payload substitution.

**Impact:** Every documented dynamic routing key configuration has no effect. Messages are
published with the wrong routing key.

**Fix:** Extend `Publisher.publish` to accept the resolved `RoutingResult`, or a `PublishContext`
carrying the routing key, pass it from `OutboxPoller`, and make `RabbitPublisher` use it. Remove
`routingKeyTemplate` from `Destination.RabbitMQ`, or make it the documented fallback when the route
does not set one. State the precedence in the README.

**DoD:**
- A unit test asserts `RabbitPublisher` publishes with the routing key supplied by the router.
- An integration test binds a queue to an exchange with routing key `eu.high.order.created`,
  publishes a message whose route template is
  `{{ payload.region }}.{{ payload.priority }}.{{ topic }}`, and asserts delivery.

---

### F-005 — Routing key template syntax in the README does not match the implementation

**Severity:** Blocker (documentation)
**Files:** `README.md` "Routing Key Templates", `outbox-service/src/main/kotlin/RoutingKeyRenderer.kt:39-50`

The README states the template variables are `{{ topic }}`, `{{ fieldName }}` for "any top-level
field from the message payload", and `{{ customer.region }}` for nested fields. `resolveField`
resolves only `topic`, `payload.*`, and `data.*`. Every other placeholder silently becomes the
default value.

**Fix:** Pick one syntax. Recommended: keep the explicit `payload.` and `data.` prefixes because
they are unambiguous, and correct the README. If bare field names are wanted, add a fallback
branch that treats an unknown name as a top-level payload field, and keep `topic` reserved.

**DoD:** A table-driven test enumerates every placeholder form shown in the README and asserts the
rendered output. The README section names that test file as the source of truth.

---

### F-006 — Messages stuck in `processing` are never recovered

**Severity:** Blocker
**Files:** `postgres/src/main/kotlin/OutboxRepository.kt:29-46`, `postgres/src/main/kotlin/InboxRepository.kt:47-104`

`claimBatch` sets `state = 'processing'` and the poller then publishes outside that transaction. If
the process is killed, the pod is evicted, or the publish coroutine dies, the row stays
`processing` forever. No component reclaims it. The same applies to the inbox.

**Impact:** Permanent message loss after any crash. This is the most important defect for a
reliability product.

**Fix:**
1. Add a `claimed_at TIMESTAMPTZ` column to both tables in a new migration
   (`V3__add_claimed_at.sql` for PostgreSQL, `V2__add_claimed_at.sql` for SQL Server), and set it
   in `claimBatch` and `claimPending`.
2. Add `reclaimStale(olderThan: Duration): Int` to both repository interfaces. It moves rows from
   `processing` back to `pending` when `claimed_at` is older than the visibility timeout.
3. Add configuration `outbox.claimTimeoutMs` (default `300000`) and `inbox.claimTimeoutMs`.
4. Call `reclaimStale` from the poller loop, at most once per `claimTimeoutMs / 5`.
5. Add metric `queuebox_outbox_messages_reclaimed_total`.

**DoD:**
- An integration test inserts a row, claims it, back-dates `claimed_at`, runs `reclaimStale`, and
  asserts the row is `pending` again with the attempt count unchanged.
- An end to end test cancels the poller between claim and publish and asserts the message is
  delivered after a restart.

---

### F-007 — `deleteInBatches` uses outbox states when cleaning the inbox, so inbox age retention deletes nothing

**Severity:** Blocker
**File:** `outbox-service/src/main/kotlin/RetentionService.kt:107-124`, called from line 90

`deleteInBatches` iterates `outboxCompletedStates` (`sent`, `dead`) for both tables. `cleanupInbox`
passes `inboxRepository::deleteOlderThan`, so the inbox is asked to delete rows in states `sent` and
`dead`. The inbox never holds those states, so zero rows are deleted and the cleanup metric reports
a successful run.

**Fix:** Pass the state list into `deleteInBatches` as a parameter. Use `inboxCompletedStates` for
the inbox.

**DoD:** A test inserts inbox rows in state `processed` older than the cutoff, runs the retention
cycle, and asserts the rows are deleted and
`queuebox_cleanup_messages_deleted_total{table="inbox"}` increased by the same number.

---

### F-008 — Retention "batching" is not batched; a cleanup can lock the whole table

**Severity:** Blocker
**Files:** `outbox-service/src/main/kotlin/RetentionService.kt:107-124`,
`postgres/src/main/kotlin/OutboxRepository.kt:85-89`

`deleteInBatches` loops while `deleted >= batchSize`, but `deleteOlderThan` has no limit clause and
deletes every matching row in one statement. On a large table this is one long transaction holding
locks. The loop therefore always terminates after one pass, and `batchSize` has no effect.

**Fix:** Add a `limit: Int` parameter to `deleteOlderThan` and `deleteExceptMostRecent` on both
repository interfaces. Implement with `DELETE ... WHERE id IN (SELECT id ... LIMIT n)` on
PostgreSQL and `DELETE TOP (n)` on SQL Server.

**DoD:** A test inserts 250 eligible rows with `batchSize = 100`, asserts each delete call returns
at most 100 and three calls occur, and asserts all 250 rows are gone.

---

### F-009 — The outbox claim has no ordering and no `SKIP LOCKED`

**Severity:** Blocker
**File:** `postgres/src/main/kotlin/OutboxRepository.kt:29-46`

The claim is `selectAll().where(...).limit(batchSize).forUpdate()` with no `ORDER BY`. Two
consequences:

1. No FIFO guarantee. Row order is whatever the plan returns, so an old message can starve behind
   newer ones.
2. Without `SKIP LOCKED`, a second replica blocks on the first replica's locks for the whole batch
   duration instead of taking different work. Throughput does not scale with replicas.

**Fix:** Add `ORDER BY scheduled_at ASC, created_at ASC` and `FOR UPDATE SKIP LOCKED`. Prefer the
single-statement `UPDATE ... RETURNING` form described in F-001, so claim and mark are atomic.

**DoD:**
- A test asserts that with 10 pending rows of increasing `scheduled_at`, `claimBatch(3)` returns
  the three oldest, in order.
- A concurrency test with two claimers over 100 rows asserts disjoint result sets and that neither
  claimer blocks for more than one second.

---

### F-010 — `TransformEngine` mutates its cache inside `computeIfAbsent`

**Severity:** Blocker
**File:** `outbox-service/src/main/kotlin/transform/TransformEngine.kt:83-91`

The mapping function passed to `ConcurrentHashMap.computeIfAbsent` calls `expressionCache.remove`.
The `ConcurrentHashMap` contract forbids modifying the map from inside the mapping function. Doing
so can deadlock the bin or corrupt the map. This runs on the hot path for every transform once the
cache is full.

**Fix:** Replace the hand-rolled cache with a bounded LRU. Either a synchronised
`LinkedHashMap` with `removeEldestEntry`, or add Caffeine to the version catalog and use
`Caffeine.newBuilder().maximumSize(maxCacheSize)`. Prefer Caffeine.

**DoD:** A test fills the cache past `maxCacheSize` from 8 threads for 5 seconds and asserts no
exception, no hang (use `assertTimeoutPreemptively`), and `cacheSize() <= maxCacheSize`.

---

### F-011 — Configured table names are interpolated into raw SQL without validation

**Severity:** Blocker (security)
**Files:** `postgres/src/main/kotlin/InboxRepository.kt:52-83`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt`,
`config/src/main/kotlin/ConfigValidator.kt` (`validateColumnMapping`)

`ConfigValidator` validates column names against a SQL identifier regular expression, which is
correct. It does not validate `database.outboxTableName` or `database.inboxTableName`, and both are
interpolated into raw SQL strings.

**Impact:** A configuration value, which in container deployments comes from an environment
variable that a lower-trust layer may set, becomes arbitrary SQL.

**Fix:** Apply the same identifier regular expression to both table names. Also quote every
interpolated identifier: double quotes for PostgreSQL, square brackets for SQL Server. Do both;
validation alone is not defence in depth.

**DoD:**
- `ConfigValidatorTest` asserts that `outboxTableName = "outbox; DROP TABLE users --"` throws with
  a message naming the field and the environment variable.
- A test asserts a legitimate name such as `my_schema_outbox` still works end to end.

---

### F-012 — README declares MIT but the repository has no LICENSE file

**Severity:** Blocker (legal)
**File:** `README.md` final section

Without a `LICENSE` file the code is under exclusive copyright by default. No company can adopt it,
and GitHub will not detect a license. This alone disqualifies the repository from serious use.

**Fix:** Add `LICENSE` with the full MIT text, the correct copyright holder, and the year. Add a
licensing statement to `CONTRIBUTING.md` saying contributions are accepted under MIT.

**DoD:** `LICENSE` exists at the repository root, GitHub's license detector recognises it, and the
README links to it.

---

## 5. Phase 2 — Durability and correctness (Major)

### F-013 — A repository failure inside a batch aborts the remaining messages

**File:** `outbox-service/src/main/kotlin/OutboxPoller.kt:48-51`

`messages.forEach { processMessage(it) }` has no per-message try/catch. Any exception from the
repository or the router propagates to the loop handler at line 30, which logs and sleeps. Every
message after the failing one in that batch stays `processing` until the F-006 reclaim exists.

**Fix:** Wrap each `processMessage` call in try/catch. On failure, apply the retry strategy for that
one message and continue the batch. Add metric `queuebox_outbox_process_errors_total`.

**DoD:** A test with a repository that throws on the third of five messages asserts the other four
are delivered and the third is scheduled for retry.

### F-014 — Messages are processed strictly sequentially

**File:** `outbox-service/src/main/kotlin/OutboxPoller.kt:48-51`

One slow HTTP destination with a 30 second timeout stalls the whole batch. Throughput is one
message per round trip.

**Fix:** Add `outbox.concurrency` (default 8). Process the batch inside `coroutineScope` with a
bounded `Semaphore`. Document the ordering consequence in the README.

**DoD:** A test with 10 messages against a destination with 200 ms latency completes in under one
second with `concurrency = 8`.

### F-015 — `countByState("pending")` runs on every poll cycle

**File:** `outbox-service/src/main/kotlin/OutboxPoller.kt:43-46`

With the default `pollIntervalMs = 100` this counts the pending partition 10 times a second, only
to feed a gauge.

**Fix:** Update the gauge at most once every `outbox.pendingGaugeIntervalMs` (default 5000).

**DoD:** A test with a counting repository asserts at most one count call per 5 seconds of virtual
time.

### F-016 — The publish error is discarded; the outbox has no error column

**Files:** `outbox-service/src/main/kotlin/OutboxPoller.kt:125-134`,
`postgres/src/main/kotlin/OutboxRepository.kt:52-60`

`handlePublishFailure` accepts `error: Throwable` and never uses it. `markFailed(id, error)` exists
on the interface and is never called from production code. An operator inspecting a dead message
cannot see why it died.

**Fix:** Add a `last_error TEXT` column in a migration. Persist a truncated error string, for
example 2000 characters, on retry and on dead-letter. Redact known secret-bearing headers.

**DoD:** A test asserts that after a 500 response the row's `last_error` contains the status code
and does not contain the destination `Authorization` header value.

### F-017 — `markFailed` and `scheduleRetry` both increment `attempt`

**File:** `postgres/src/main/kotlin/OutboxRepository.kt:52-72`

Two methods increment `attempt`. If both are called for one failure, the attempt count doubles and
`maxAttempts` is reached early.

**Fix:** Make `scheduleRetry` the only method that increments. Have `markFailed` record the state
and the error only, or delete it once F-016 folds the error into `scheduleRetry`.

**DoD:** A test asserts `attempt` increases by exactly one per failed delivery across five retries.

### F-018 — The RabbitMQ consumer acknowledges from multiple coroutines on one channel

**File:** `rabbitmq/src/main/kotlin/RabbitConsumer.kt:55-58,120-134`

`handleDelivery` launches a coroutine per message on `Dispatchers.IO`, and each coroutine calls
`basicAck` or `basicNack` on the shared `Channel`. An AMQP `Channel` is not thread safe. Concurrent
acknowledgements can corrupt the channel state and close the connection.

**Fix:** Serialise acknowledgements. Either confine all channel operations to a single-threaded
dispatcher, or send acknowledgement commands to an actor `Channel<AckCommand>` consumed by one
coroutine.

**DoD:** An integration test publishes 500 messages with `prefetchCount = 50` and asserts all are
acknowledged, the AMQP channel is still open, and no message is redelivered.

### F-019 — `RabbitConsumer.stop` cancels in-flight work before acknowledgement

**File:** `rabbitmq/src/main/kotlin/RabbitConsumer.kt:179-183`

`stop` calls `scope.cancel()` immediately. A message that was stored but not yet acknowledged is
redelivered after restart. Deduplication catches that, but the shutdown is not deterministic and the
duplicate depends on the unique index rather than on design.

**Fix:** Cancel the consumer tag first with `basicCancel`, then wait for in-flight jobs with a
bounded timeout, then close the channel.

**DoD:** A test asserts that after `stop()` every message that reached the database was
acknowledged.

### F-020 — `RabbitPublisher` opens and closes a channel and redeclares the exchange per message

**File:** `rabbitmq/src/main/kotlin/RabbitPublisher.kt:24-71`

Per message it calls `getChannel`, `confirmSelect`, `exchangeDeclare`, publish, `waitForConfirms`,
and `close`. Channel creation is a network round trip. Throughput will be poor and broker channel
churn high.

**Fix:** Cache one confirm-enabled channel per destination, guarded by a mutex, and declare the
exchange once at startup. Recreate the channel on error.

**DoD:** A test publishes 1000 messages and asserts fewer than 10 channels are created.

### F-021 — Publisher confirms are awaited synchronously per message

**File:** `rabbitmq/src/main/kotlin/RabbitPublisher.kt:62`

`waitForConfirms(5000)` blocks per message. With F-014 unfixed this caps throughput at one message
per broker round trip.

**Fix:** After F-014 and F-020, use asynchronous confirms with a sequence-number map, or keep the
synchronous form and document the measured throughput.

**DoD:** The README states a measured throughput figure and names the test that produced it.

### F-022 — The `mandatory` publish flag is set but no return listener is registered

**File:** `rabbitmq/src/main/kotlin/RabbitPublisher.kt:59`

`basicPublish(..., mandatory = true, ...)` asks the broker to return unroutable messages. No
`ReturnListener` is registered, so a returned message is dropped silently while `waitForConfirms`
still reports success. Unroutable messages are marked `sent`.

**Fix:** Register a `ReturnListener`, correlate returns to the pending confirm, and fail the publish
so the retry or dead-letter path runs.

**DoD:** A test publishes to a topic exchange with no matching binding and asserts the outbox row is
not marked `sent`.

### F-023 — No inbound request size limit

**Files:** `inbox-service/src/main/kotlin/InboxRoutes.kt:31-61`, `app/src/main/kotlin/App.kt:201`

`call.receive<ByteArray>()` and `call.receive<JsonElement>()` read the whole body into memory with
no cap. With `DoubleReceive` and `cacheRawRequest = true` the body is buffered as well. A single
large POST can exhaust the heap.

**Fix:** Add `inbox.maxBodyBytes` (default 1048576). Reject larger bodies with 413 before reading.
Configure the Netty engine request size limit and cap `DoubleReceive` buffering.

**DoD:** A test posts a body one byte over the limit and asserts 413, with no buffer allocated
beyond the limit.

### F-024 — The inbox HTTP endpoint has no rate limiting

**File:** `inbox-service/src/main/kotlin/InboxRoutes.kt`

A webhook receiver exposed to the internet with no rate limiting can be used as an amplification
target against its own database.

**Fix:** Install Ktor's `RateLimit` plugin with per-source configuration
(`sources.<name>.rateLimit.requestsPerMinute`, optional). Return 429 with `Retry-After`.

**DoD:** A test asserts the 61st request in a minute against a source limited to 60 returns 429.

### F-025 — Idempotency extraction serialises the payload to a string for every message

**File:** `core/src/main/kotlin/IdempotencyExtractor.kt:15-26`

`Json.encodeToString(payload)` followed by `JsonPath.parse(jsonString)` re-serialises and re-parses
every message, once per configured path.

**Fix:** Parse once per message and reuse the `DocumentContext`. Change the signature to accept a
pre-parsed context, or add `extractAll(payload, paths): Map<String, String?>`.

**DoD:** A call-counting test asserts one parse per message regardless of the number of configured
paths.

### F-026 — Topic pattern regular expressions are recompiled per message and are not anchored or validated

**File:** `outbox-service/src/main/kotlin/MessageRouter.kt:66-76`

`matchesPattern` builds a `Regex` on every call for every route. The temporary placeholder used to
protect the `**` wildcard collides if a topic or pattern contains that same literal. Only `.` is
escaped, so other regular expression metacharacters in a pattern are interpreted. Patterns come from
configuration and are never validated, so a pathological pattern is a denial of service on the poller
thread.

**Fix:** Precompile every route pattern once in the `MessageRouter` constructor. Escape all
metacharacters with `Regex.escape` on the literal segments rather than string replacement. Build the
regular expression by splitting on the wildcards instead of using a placeholder token. Validate
patterns in `ConfigValidator`.

**DoD:** A test asserts topics containing `+`, `(`, `[`, and the previous placeholder token match
correctly, and a test asserts patterns are compiled once for N messages.

### F-027 — `RetentionService.stop` can block for the whole cleanup interval

**File:** `outbox-service/src/main/kotlin/RetentionService.kt:128-133`

`stop` sets `running` to false and then joins the children. A child sitting in `delay(interval)`
with a one hour interval does not return until the delay elapses, because `join` does not cancel it.
Shutdown hangs until the container is killed.

**Fix:** Cancel the scope inside a bounded `withTimeout`, or replace the `delay` loop with a
cancellable ticker and use `cancelAndJoin`.

**DoD:** A test with a one hour interval asserts `stop()` returns in under two seconds.

### F-028 — `OutboxPoller.shutdown` has the same unbounded wait

**File:** `outbox-service/src/main/kotlin/OutboxPoller.kt:138-143`

The same join-before-cancel pattern. With the default 100 ms interval it is not visible, but it
becomes visible with a long poll interval or a long in-flight publish.

**Fix:** Use `withTimeout(shutdownTimeoutMs)` around the joins, then `cancel()`. Add
`outbox.shutdownTimeoutMs` (default 30000).

**DoD:** A test with a publisher that never returns asserts `shutdown()` returns within the timeout
and logs the abandoned message count.

### F-029 — The Ktor server is not stopped during shutdown

**File:** `app/src/main/kotlin/App.kt:184-207`

The shutdown hook closes the pollers, the HTTP client, and the data source, but never calls `stop()`
on the embedded server. In-flight inbox requests can reach a closed data source and fail with a
confusing error. There is no connection draining.

**Fix:** Hold the `EmbeddedServer` reference. In the hook call `server.stop(gracePeriodMillis,
timeoutMillis)` first, then stop the background services, then close the resources.

**DoD:** A test issues a slow inbox request, triggers shutdown, and asserts the request completes
with a success status rather than 500.

### F-030 — Migrations exist but nothing runs them

**Files:** `postgres/src/main/resources/db/migration/`, `sqlserver/src/main/resources/db/migration/`

The layout is Flyway's, but Flyway is not a dependency and no code applies migrations. The only path
that creates schema is the Compose file mounting the SQL into `/docker-entrypoint-initdb.d`, which
runs once, only for PostgreSQL, and only on a fresh volume. The README "Manual Setup" tells the user
to create the database but never to create the tables.

**Fix:** Add Flyway to the version catalog, run `Flyway.migrate()` at startup behind
`database.migrate` (default true), and document how to disable it where the application user has no
DDL rights. Keep the SQL files as the documented manual alternative.

**DoD:** An end to end test starts against an empty PostgreSQL container with no init scripts and
succeeds. The same for SQL Server.

### F-031 — SQL Server has one migration file containing two tables while PostgreSQL has two

**Files:** `sqlserver/src/main/resources/db/migration/V1__create_tables.sql`,
`postgres/src/main/resources/db/migration/V1__create_outbox.sql`, `V2__create_inbox.sql`

Divergent numbering across providers becomes error prone once Flyway is introduced (F-030).

**Fix:** Align the file naming and numbering. Document the migration policy in
`docs/development/migrations.md`: one logical change per file, never edit a released file.

**DoD:** The document exists and the two file sets correspond one to one.

### F-032 — PostgreSQL raw SQL does not quote identifiers while SQL Server does

**Files:** `postgres/src/main/kotlin/InboxRepository.kt:52-83`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt` (`escapeSqlServerColumnName`)

The SQL Server implementation escapes reserved words. The PostgreSQL implementation does not, so a
custom column mapping using a reserved word such as `key`, `order`, or `user` fails at runtime with
a syntax error, although the feature is documented as supporting existing schemas.

**Fix:** Add an identifier quoting helper per provider and use it for every interpolated identifier.

**DoD:** A test configures a column mapping using `order` and `user` and passes end to end on both
providers.

### F-033 — Outbox retention age uses `updated_at`, inbox uses `created_at`, and neither is documented

**Files:** `postgres/src/main/kotlin/OutboxRepository.kt:85-89`,
`postgres/src/main/kotlin/InboxRepository.kt:122-126`

The semantics differ between tables and the README does not say which timestamp the age policy uses.
An operator sizing retention cannot predict the behaviour.

**Fix:** Document both. Prefer `updated_at` where it exists, and either add `updated_at` to the
inbox or state explicitly that inbox age is measured from receipt.

**DoD:** The README retention section states the exact column per table, and a test asserts it.

---

## 6. Phase 3 — Security hardening

### F-034 — The `/admin/transform/test` endpoint is unauthenticated

**Severity:** Blocker
**File:** `app/src/main/kotlin/AdminRoutes.kt:19-98`

Anyone who can reach the port can execute arbitrary JSONata expressions with a caller-supplied
payload and a caller-supplied, unbounded timeout (`request.timeoutMs`). This is remote compute on
the message-processing host, and the unbounded timeout makes it a denial of service.

**Fix:**
1. Require authentication. Add `admin.auth` using the existing `InboxAuthConfig` types, and refuse
   to start with admin routes enabled and no auth configured unless `admin.insecure = true` is set
   explicitly.
2. Add `admin.enabled` (default false).
3. Clamp `timeoutMs` to `admin.maxTransformTimeoutMs` (default 1000) and clamp the payload size.

**DoD:** Tests assert 401 without credentials, success with credentials, and that a request asking
for `timeoutMs = 600000` is clamped. A test asserts the application refuses to start in the insecure
combination.

### F-035 — HMAC replay protection does not cover the timestamp

**File:** `inbox-service/src/main/kotlin/auth/InboxAuthValidator.kt:67-98`

The timestamp header is compared against the clock, but it is not part of the signed payload. The
signature covers the body only. An attacker who captures one valid request can replay it with a
fresh timestamp header for as long as the body stays valid.

**Fix:** Sign `timestamp + "." + body`, as Stripe and GitHub do. Make the signed form explicit and
configurable (`signaturePayloadFormat`), and default to the timestamped form when `timestampHeader`
is configured.

**DoD:** A test replays a captured request with an updated timestamp and asserts 401.

### F-036 — Bearer token validation accepts a token without the scheme

**File:** `inbox-service/src/main/kotlin/auth/InboxAuthValidator.kt:44-54`

`header.removePrefix("Bearer ")` leaves the header unchanged when the prefix is missing, so
`Authorization: <token>` is accepted. The comparison is also case sensitive, so `bearer <token>`
fails, although the scheme is case insensitive per RFC 7235.

**Fix:** Parse the header into scheme and credentials. Compare the scheme case insensitively. Reject
any other scheme.

**DoD:** Table-driven tests cover `Bearer x`, `bearer x`, `x`, `Basic x`, and an empty header.

### F-037 — `secureCompare` is not the recommended constant-time primitive

**File:** `inbox-service/src/main/kotlin/auth/InboxAuthValidator.kt:113-120`

The early return on differing lengths leaks the secret length, and the character loop is a
hand-rolled primitive.

**Fix:** Use `java.security.MessageDigest.isEqual` on the UTF-8 bytes. To remove the length leak,
compare digests of both values rather than the raw values.

**DoD:** The implementation calls `MessageDigest.isEqual`, and a test asserts the behaviour is
unchanged for equal and unequal inputs.

### F-038 — Configuration data classes print secrets in `toString`

**Files:** `config/src/main/kotlin/QueueBoxConfig.kt:24-34`, `config/src/main/kotlin/AuthConfig.kt`,
`core/src/main/kotlin/DestinationAuthConfig.kt`

`DatabaseConfig`, `InboxAuthConfig.Bearer`, `InboxAuthConfig.ApiKey`, `InboxAuthConfig.HmacSignature`,
`DestinationAuthConfig.OAuth2`, and `DestinationAuthConfig.Basic` are data classes holding plaintext
secrets. Any log line, exception message, or crash dump that includes the configuration leaks every
credential. Hoplite error messages frequently include loaded configuration values.

**Fix:** Introduce a `Secret` value class wrapping a `String` whose `toString` returns a mask, and
use it for every credential field. Override `toString` on the enclosing data classes as well.

**DoD:** A test asserts `config.toString()` contains no configured secret value, enumerating every
credential field explicitly.

### F-039 — HTTP error response bodies are captured into exceptions unbounded

**File:** `outbox-service/src/main/kotlin/http/HttpPublisher.kt:76-82`

`response.bodyAsText()` reads the entire error body of a failing destination into an exception that
will be logged and, after F-016, persisted. A hostile or broken destination can return megabytes, and
error bodies frequently echo the request, including authorization headers.

**Fix:** Truncate to `http.maxErrorBodyBytes` (default 2048) and redact credential patterns before
the value leaves the publisher.

**DoD:** A test with a 1 MB error body asserts the exception message is at most the configured size.

### F-040 — Destination URLs are not validated, allowing requests to internal addresses

**Files:** `config/src/main/kotlin/ConfigValidator.kt`,
`outbox-service/src/main/kotlin/http/HttpPublisher.kt:59`

`baseUrl + path` is concatenated without validation. There is no scheme allowlist and no protection
against a destination pointing at a link-local metadata address. Where configuration comes from the
environment this is server-side request forgery.

**Fix:** Validate that `baseUrl` parses as an absolute HTTP or HTTPS URL at startup. Add optional
`http.blockPrivateAddresses` (default false, documented) that refuses loopback, link-local, and
private ranges. Join the base URL and the path with a URL builder rather than string concatenation,
so a missing or duplicated slash cannot change the target.

**DoD:** Tests cover a missing scheme, a `file://` scheme, a double slash join, and, with the flag
on, `http://169.254.169.254/`.

### F-041 — No TLS guidance and no HTTPS enforcement

The server listens on plain HTTP and the documentation never mentions TLS termination.

**Fix:** State in the README that QueueBox expects TLS termination at the ingress, or add
`server.ssl` configuration. Document the recommended path in the operations guide.

**DoD:** The operations document has a "Transport security" section with a working ingress example.

### F-042 — The Docker image runs a JRE that will not receive long-term updates

**File:** `Dockerfile`

`eclipse-temurin:23-jre-alpine` is a non-LTS release. With `jvmToolchain(23)` this forces every
adopter onto a short-lived JDK.

**Fix:** Target Java 21 LTS in the toolchain and in the image. See F-062.

**DoD:** `docker build` succeeds and the image reports Java 21.

### F-043 — No dependency vulnerability scanning and no SBOM

There is no dependency check, no Dependabot or Renovate configuration, and no SBOM output.

**Fix:** Add Dependabot or Renovate for Gradle and for GitHub Actions. Add the CycloneDX Gradle
plugin and publish the SBOM with each release. Add a scan job to CI that fails on high severity
findings, with a documented suppression file.

**DoD:** CI has a `security` job. A release produces `queuebox-<version>-sbom.json`.

### F-044 — The container image is not scanned and is not reproducible

**File:** `Dockerfile`

No base image digest pin, no build attestation, no image scan.

**Fix:** Pin base images by digest. Add a Trivy or Grype scan step to CI. Produce provenance
attestation with buildx.

**DoD:** CI fails on a critical image finding, and the Dockerfile references digests.

### F-045 — Secrets are documented as plain YAML values with no secret manager story

**File:** `README.md` configuration sections

Every credential example is a literal or a shell environment variable. There is no guidance for
Kubernetes secrets, mounted files, or a secret manager.

**Fix:** Support a `file:` prefix on any secret field, so `password: file:/run/secrets/db` reads the
file at startup. Document the Kubernetes secret pattern.

**DoD:** A test asserts a `file:` reference resolves and that the file contents never appear in a log
line.

---

## 7. Phase 4 — Observability and operations

### F-046 — There is no logging framework; the code uses `println`

**Severity:** Blocker
**Files:** 20 occurrences across `src/main`, including `App.kt:185,197`, `OutboxPoller.kt:32`,
`RetentionService.kt:59,96`, `RabbitConsumer.kt:99,128,133`.

There is no SLF4J binding in any module, so Ktor's own logging is a no-op, and every QueueBox
diagnostic goes to standard output unstructured, with no level, no timestamp, and no correlation. No
operator can run this in production.

**Fix:** Add `logback-classic` to the version catalog and to the `app` runtime. Replace every
`println` with a named logger call at the correct level. Add `logback.xml` with a JSON encoder
profile selectable by `LOG_FORMAT=json|text`. Put `messageId`, `topic`, `destination`, and `attempt`
in the MDC for message-scoped lines.

**DoD:** `grep -rn "println(" --include='*.kt' */src/main` returns zero results. A test asserts a
failed publish emits one WARN line containing the message ID.

### F-047 — No correlation identifier propagation

An inbox request, the stored row, the outbox row, and the outbound publish share no identifier.
Debugging a delivery across the system is manual.

**Fix:** Accept and generate `X-Correlation-Id` on inbox requests, store it, and forward it on
outbound publishes and in the MDC.

**DoD:** An end to end test asserts the same correlation identifier appears on the inbound request,
in the database row, and on the outbound request.

### F-048 — No tracing

The README roadmap lists OpenTelemetry. For a routing product, tracing is close to mandatory.

**Fix:** Either implement OpenTelemetry spans for receive, claim, transform, and publish, or move the
item into a clearly labelled "not planned for 1.0" section. Do not leave an unqualified promise.

**DoD:** Either the traces exist and are documented, or the roadmap states the target version.

### F-049 — `/health` does not distinguish liveness from readiness

**File:** `app/src/main/kotlin/HealthRoutes.kt`

One endpoint checks the database with a five second validation. Kubernetes needs a cheap liveness
probe and a dependency-aware readiness probe. A slow database will fail the liveness probe and kill
the pod in a loop.

**Fix:** Add `/health/live` (process only, no I/O) and `/health/ready` (database, broker, workers).
Keep `/health` as an alias of readiness for compatibility and document it.

**DoD:** Tests assert `/health/live` returns 200 with a broken data source and `/health/ready`
returns 503.

### F-050 — Health checks do not cover RabbitMQ or the background services

**File:** `app/src/main/kotlin/HealthManager.kt:17-34`

Only the data source is checked. A dead poller or a disconnected broker reports healthy.

**Fix:** Register health contributors for the poller (last successful cycle within N intervals), the
retention service, and each RabbitMQ connection.

**DoD:** A test stops the poller and asserts readiness turns unhealthy with a named component.

### F-051 — `/metrics` is unauthenticated and served on the data port

Prometheus metrics reveal traffic volumes and destination names. Serving them on the public data port
is an information leak in most deployments.

**Fix:** Add `server.managementPort` (optional). When set, serve `/metrics`, `/health/*`, and
`/admin` on that port only.

**DoD:** A test asserts `/metrics` returns 404 on the data port when a management port is set.

### F-052 — Metric gaps

No metric exists for: reclaimed messages (F-006), per-destination success and failure counts,
transform failures by strategy, inbox rejections by reason, HTTP status code distribution, or queue
depth by destination.

**Fix:** Add counters with bounded label sets. Never use a message ID or a raw error string as a
label.

**DoD:** The README metrics table lists every emitted metric, and a test scrapes `/metrics` and
asserts each documented name is present.

### F-053 — The application version is hard coded in a metric

**File:** `core/src/main/kotlin/metrics/QueueBoxMetrics.kt:88-91`

`.tag("version", "0.1.0")` is a string literal. No Gradle project version exists, so this will
silently become wrong at the first release.

**Fix:** Set `version` in the root `build.gradle.kts`, generate a `BuildInfo` object or a generated
resource, and read it here.

**DoD:** A test asserts the metric tag equals the Gradle project version.

### F-054 — No operations runbook

There is no document covering how to inspect dead-lettered messages, how to replay them, what to do
when the pending gauge grows, how to size the pool and the batch, or what to check on a slow
destination.

**Fix:** Write `docs/operations/runbook.md` covering those five scenarios with concrete SQL and
commands.

**DoD:** The document exists and a test executes every SQL statement in it against the shipped
schema.

### F-055 — No dead-letter inspection or replay path

Once a message is `dead` there is no supported way to see it or retry it. The roadmap lists replay,
but adopters need at least documented SQL now.

**Fix:** Document the SQL to list dead messages and to requeue one: set the state to `pending`, reset
`attempt`, and clear `scheduled_at`. Add `docs/operations/dead-letter.md`.

**DoD:** The documented SQL is executed by an integration test that asserts the requeued message is
delivered.

### F-056 — No graceful degradation when the database is unavailable at startup

**File:** `app/src/main/kotlin/App.kt:35-36`

`DatabaseFactory.create` and `init` throw if the database is not yet up, and the process exits. In an
orchestrator this produces a crash loop with no useful message.

**Fix:** Retry the initial connection with backoff for `database.startupTimeoutMs` (default 60000)
and log one clear message per attempt. Fail after the timeout with an actionable error.

**DoD:** A test starts the application against a database that becomes available after five seconds
and asserts a successful start.

### F-057 — Transform expressions and topic patterns are not validated at startup

**Files:** `config/src/main/kotlin/ConfigLoader.kt`,
`outbox-service/src/main/kotlin/transform/TransformEngine.kt:74`

`validateExpression` documents itself as "should be called at startup to fail fast" and is called
only from the admin endpoint and from tests. An invalid JSONata expression in configuration is
discovered on the first message, per message, forever.

**Fix:** Compile every configured transform expression at startup and fail with the configuration
path of the offending expression. Do the same for topic patterns (F-026).

**DoD:** A test with a syntactically invalid destination transform asserts startup fails with a
message naming `destinations.<name>.transform.expression`.

---

## 8. Phase 5 — Open source governance

### F-058 — Missing LICENSE

See F-012. Repeated here because it belongs to the governance set.

### F-059 — No continuous integration

There is no `.github/` directory. Nothing verifies a pull request.

**Fix:** Add `.github/workflows/ci.yml` running on push and pull request:
- `build`: `./gradlew build` on Java 21.
- `test`: `./gradlew check jacocoAggregatedReport` with Docker available for Testcontainers,
  uploading the coverage report as an artifact.
- `lint`: ktlint and detekt (F-066).
- `docker`: build the image and run the Trivy scan (F-044).
- Matrix the test job across PostgreSQL 14, 15, and 16, and SQL Server 2019 and 2022, to back the
  compatibility claims in the README.

**DoD:** A pull request shows the jobs green. The README carries a CI badge that resolves.

### F-060 — No SECURITY.md

For a project handling credentials and webhooks, a vulnerability reporting path is expected.

**Fix:** Add `SECURITY.md` with a supported versions table, a private reporting channel, and a
response time commitment.

**DoD:** The file exists and GitHub shows the security policy link.

### F-061 — No CONTRIBUTING.md, CODE_OF_CONDUCT.md, CODEOWNERS, or issue and pull request templates

**Fix:** Add all of them. `CONTRIBUTING.md` must contain the prerequisites (JDK, Docker), the exact
build and test commands, the code style rules, the commit message convention, the branch strategy,
how to run only the fast tests, and how to add a migration.

**DoD:** A person who has never seen the repository can clone it and reach a green `./gradlew check`
using only `CONTRIBUTING.md`. Verify by following the document literally in a clean container and
recording the transcript.

### F-062 — The build requires Java 23 while the README says Java 21

**Files:** `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` (`jvmToolchain(23)`),
`buildSrc/build.gradle.kts`, `Dockerfile`, `README.md` "Requirements: JDK 21+"

Java 23 is not an LTS release. Most enterprise adopters are on 17 or 21, and the documentation
contradicts the build.

**Fix:** Set the toolchain to 21 and keep the bytecode target at 21. State the supported range in the
README and enforce it in the CI matrix.

**DoD:** `./gradlew build` succeeds on a Java 21 toolchain in CI.

### F-063 — The project is not published anywhere and has no version

**Files:** `build.gradle.kts` (no `group`, no `version`), no `maven-publish` plugin

There is no way to depend on QueueBox and no released image. The README's Docker instructions tell
the reader to build and push under their own account.

**Product decision (made by the maintainer, authoritative):** container image only. Do not publish
Maven artifacts. Do not add the `maven-publish` plugin, signing, or POM metadata. QueueBox ships as
a deployable service, not as a library.

**Fix:**
1. Set `version` in the root `build.gradle.kts`, derived from the Git tag, with a
   `0.0.0-SNAPSHOT` fallback for an untagged build. Set `group = "org.nxtspec"` for consistency
   only.
2. Add `.github/workflows/release.yml`, triggered on a `v*` tag. It builds the image with buildx
   for `linux/amd64` and `linux/arm64`, and pushes to GHCR tagged with the exact version, the
   minor version, and `latest`.
3. Attach the SBOM (F-043) and the provenance attestation (F-044) to the release.
4. Rewrite the README "Building the Docker Image" section. The Quick Start must pull the published
   image. Keep the local build instructions in `docs/development/`, not in the README.

**DoD:** A tagged release produces a multi-architecture image in GHCR that
`docker run ghcr.io/<org>/queuebox:<version>` starts, and the README Quick Start uses that image
rather than a local build. `grep -rn "maven-publish" --include='*.gradle.kts' .` returns nothing.

### F-064 — No changelog and no release process

**Fix:** Add `CHANGELOG.md` following Keep a Changelog, and `docs/development/releasing.md`
describing the tag, the workflow, and the version bump. Adopt semantic versioning and state the
compatibility policy for the configuration schema and the database schema.

**DoD:** The 0.1.0 entry exists and the release workflow produces the artifacts named in F-063.

### F-065 — Agent tooling is committed to the repository

**Files:** `.taskmaster/` (tasks, PRDs, reports), `.cursor/` (50 command files), `.claude/`,
`.mcp.json`, `CLAUDE.md`

These are personal workflow files. In a public repository they add noise, they publish internal
planning documents, and `.mcp.json` together with `.env.example` advertise AI provider key names,
which confuses readers about what the project needs.

**Product decision (made by the maintainer, authoritative):** preserve nothing. No Task Master
planning content moves into `docs/`.

**Fix:** Remove `.taskmaster/`, `.cursor/`, `.claude/`, `.mcp.json`, and `CLAUDE.md` from version
control with `git rm -r --cached`, and add each path to `.gitignore`. Delete no local files. Write
the documentation in Phase 6 from the shipped code, not from the removed planning documents.

**DoD:** `git ls-files | grep -E '^\.(taskmaster|cursor|claude)/|^\.mcp\.json$'` returns nothing.

### F-066 — No code style enforcement

There is no ktlint, no detekt, and no `.editorconfig`. Formatting is inconsistent across modules, for
example the indentation of the `forEach` block at `inbox-service/src/main/kotlin/InboxRoutes.kt:29-31`.

**Fix:** Add ktlint and detekt with a checked-in configuration, wire both into `check`, and add an
`.editorconfig`. Format the whole codebase in one dedicated commit so the diff stays reviewable.

**DoD:** `./gradlew ktlintCheck detekt` passes and CI enforces it.

### F-067 — Dependency versions are declared inline instead of in the version catalog

**Files:** `app/build.gradle.kts` (HikariCP 6.0.0, Exposed 0.56.0 four times, amqp-client 5.22.0,
ktor-client-cio 3.4.0), and the other module build files

The catalog exists but is bypassed, so a version bump means editing several files and versions can
drift between modules.

**Fix:** Move every coordinate into `gradle/libs.versions.toml`.

**DoD:** A grep for inline `group:artifact:version` strings in `*.gradle.kts` returns nothing.

### F-068 — No dependency locking or reproducible builds

**Fix:** Enable Gradle dependency verification or dependency locking, and commit the lock files.

**DoD:** A build with a tampered dependency fails verification.

### F-069 — The coverage gates are low and the per-module gate is nearly meaningless

**Files:** `build.gradle.kts` (72 percent line, 65 percent branch),
`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` (15 percent per module)

A 15 percent per-module floor allows a module to be almost untested. `TESTING.md` already notes the
targets are intermediate.

**Product decision (made by the maintainer, authoritative):** the raised thresholds are accepted.
They will block merges, and that is intended.

**Fix:** Raise the aggregate to 80 percent line and 70 percent branch, and the per-module floor to 60
percent, at the end of Phase 2, once Phases 1 and 2 have added the missing tests. Do not raise them
earlier, because a red gate during Phase 1 hides real failures. Exclude only generated code, and
justify each exclusion in `TESTING.md`.

**DoD:** `./gradlew checkCoverage` passes at 80 percent line, 70 percent branch, and 60 percent per
module. `TESTING.md` states the new numbers and no longer calls them intermediate.

### F-070 — Coverage exclusions hide real code

**File:** `build.gradle.kts` (`exclude("**/*Table.class")`, `exclude("**/*Tables.class")`)

`DynamicTables.kt` contains column mapping logic that is a documented feature and is excluded from
coverage by a filename pattern.

**Fix:** Exclude by package or by an explicit class list, not by a broad suffix pattern. Restore
coverage for the dynamic table classes.

**DoD:** The exclusion list in `TESTING.md` names each excluded class with a reason.

### F-071 — Dead template code is committed

**Files:** `core/src/main/kotlin/Main.kt`, `config/src/main/kotlin/Main.kt`,
`postgres/src/main/kotlin/Main.kt`, `inbox-service/src/main/kotlin/Main.kt`,
`outbox-service/src/main/kotlin/Main.kt`, and the whole `utils` module

Five identical IntelliJ template files with tooltip comments remain. The `utils` module contains only
a template printer and is a dependency of `app`. The JaCoCo configuration already works around the
duplicate `MainKt` classes, which is a symptom rather than a fix.

**Fix:** Delete all five files and the `utils` module. Remove `utils` from `settings.gradle.kts` and
from `app/build.gradle.kts`, and remove the `MainKt` exclusion from the coverage configuration.

**DoD:** `./gradlew build` passes and `grep -rn "Hello, " --include='*.kt' .` returns nothing.

---

## 9. Phase 6 — Documentation and polish

### F-072 — `docs/` is empty and the README carries everything

**Files:** `docs/` (empty), `README.md` (24 KB, 850 lines)

A mainstream project has a documentation set: a short README, a getting started guide, a
configuration reference, an operations guide, an architecture document, and contribution docs.

**Fix:** Restructure:

```
README.md                      # what it is, why, 60-second quick start, links
docs/getting-started.md
docs/configuration.md          # the full reference
docs/architecture.md           # module map, message lifecycle, diagrams
docs/transforms.md
docs/authentication.md
docs/operations/runbook.md
docs/operations/dead-letter.md
docs/development/contributing.md, migrations.md, releasing.md, testing.md
docs/adr/0001-....md           # architecture decision records
```

**DoD:** The README is under 200 lines and every removed section has a new home linked from it.

### F-073 — `.env.example` documents AI provider keys, not QueueBox configuration

**File:** `.env.example`

The file lists `ANTHROPIC_API_KEY`, `PERPLEXITY_API_KEY`, and nine other AI keys. QueueBox reads
`QUEUEBOX_*`. A new user who follows the file configures nothing.

**Fix:** Replace it with the QueueBox variables: `QUEUEBOX_DATABASE_URL`,
`QUEUEBOX_DATABASE_USERNAME`, `QUEUEBOX_DATABASE_PASSWORD`, `QUEUEBOX_SERVER_HTTP_PORT`, and one
example each for a destination, a route, and a source.

**DoD:** `docker compose --env-file .env.example up` starts a working instance.

### F-074 — Docker Compose sets environment variables the application does not read

**Severity:** Blocker
**Files:** `docker-compose.yml`, `docker-compose.override.yml`

Both set `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `RABBITMQ_URL`. `ConfigLoader` reads only
`QUEUEBOX_*` prefixed variables plus the packaged `queuebox.yml` resource. The documented Quick Start
therefore starts a container configured by the packaged YAML, not by Compose, and
`docker compose up -d` does not behave as the README describes.

**Fix:** Use `QUEUEBOX_DATABASE_URL`, `QUEUEBOX_DATABASE_USERNAME`, and
`QUEUEBOX_DATABASE_PASSWORD`. Mount an example `queuebox.yml` rather than relying on the packaged
one.

**DoD:** A CI job runs `docker compose up -d`, waits for health, posts a message to an inbox source,
and asserts a success response.

### F-075 — The README "Manual Setup" instructions do not work

**File:** `README.md` "Manual Setup"

It exports `DB_URL`, `DB_USER`, and `DB_PASSWORD`, which are not read (F-074), and never creates the
tables (F-030).

**Fix:** Correct the variable names and add the schema step.

**DoD:** A CI job executes the steps verbatim.

### F-076 — Configuration lives inside the build tree

**Files:** `config/src/main/resources/queuebox.yml`, README instructions to edit it

The README tells users to edit a file inside a Gradle source set, which is packaged into the jar.
Every configuration change then needs a rebuild, which is not viable for a deployed service.

**Fix:** Load configuration from an external path with `QUEUEBOX_CONFIG_FILE` (default
`/etc/queuebox/queuebox.yml`), falling back to the classpath resource. Document the precedence.

**DoD:** A test starts the application with an external file and asserts the external values win.

### F-077 — Documented state values do not match the implementation

**File:** `README.md` "Database Schema"

It documents the outbox `state` as `pending`, `sent`, `dead` and the inbox as `pending`, `processed`.
The code also writes `processing` and `failed` for the outbox and `processing` for the inbox. The
documented column width `VARCHAR(20)` does not match the migrations, which use `VARCHAR(50)`.

**Fix:** Correct the documentation and add a state transition diagram. `MessageState.canTransitionTo`
exists in `core` and is never used by the repositories; either make it authoritative or delete it.

**DoD:** The documented state set equals the set of literals the repositories write, asserted by a
test.

### F-078 — The inbox response code contradicts convention and the documentation is incomplete

**Files:** `README.md` "Inbox Endpoints", `inbox-service/src/main/kotlin/InboxRoutes.kt:64-68`

The endpoint returns 200 for an accepted message that has not been processed. 202 Accepted is the
correct code. The README also omits the 422 that `TransformFailed` returns.

**Product decision (made by the maintainer, authoritative):** make the change. There are no public
consumers yet, so no compatibility shim and no configuration flag is needed.

**Fix:** Return 202 for a new message and 200 for a duplicate, and document 422 and 413 (F-023).
Record the change in `CHANGELOG.md` under the first release, in a "Breaking changes" heading.

**DoD:** The README response table lists every code the route can return, verified by a test that
enumerates them.

### F-079 — The metrics table is incomplete

See F-052 and F-053. The README also omits the HikariCP and JVM metrics that Micrometer registers via
`MicrometerMetricsTrackerFactory` and which appear at `/metrics`.

**Fix:** Document the pool and JVM metrics.

**DoD:** The documented list equals the scraped list in a test, allowing for a documented prefix
allowlist.

### F-080 — No architecture documentation or diagrams

There is no module dependency diagram, no message lifecycle diagram, and no explanation of why the
repository layer uses reflection (`core/src/main/kotlin/repository/DatabaseProviderFactory.kt:87-99`)
to avoid module dependencies.

**Fix:** Write `docs/architecture.md` with a Mermaid module graph and a lifecycle sequence diagram.
Record the reflection decision as an ADR, including the failure mode when the provider module is
absent from the classpath, which currently throws a raw `ClassNotFoundException`.

**DoD:** The document exists, and `DatabaseProviderFactory` catches `ClassNotFoundException` and
throws a named error telling the user which module to add.

### F-081 — No examples directory

The README shows fragments. There is no runnable example.

**Fix:** Add `examples/` with at least a webhook receiver, an HTTP fan-out, and a RabbitMQ bridge.
Each with a `queuebox.yml`, a `docker-compose.yml`, and a README.

**DoD:** A CI job starts each example and runs its smoke test.

### F-082 — The roadmap makes unqualified promises

**File:** `README.md` "Roadmap"

Six unchecked items with no version and no owner. Two of them, rate limiting and replay, are gaps
adopters will hit immediately.

**Fix:** Attach a target version to each, or mark it as not planned. Move the items that Phases 3 and
4 implement out of the roadmap.

**DoD:** Every roadmap item has a milestone link or an explicit "not planned" label.

### F-083 — No badges, no project identity, no support statement

**Fix:** Add build status, coverage, license, and latest release badges. Add a repository description
and topics. State the support policy and the maintenance status in the README.

**DoD:** All badges resolve to live pages.

### F-084 — The integration contract is not documented

**Severity:** Major
**Files:** `README.md`, `postgres/src/main/resources/db/migration/`

An adopting application integrates with QueueBox by inserting an outbox row inside its own
transaction. That insert is the product surface, and no document shows it. The README explains the
QueueBox configuration in detail and shows the application side in one line: "Your application
inserts messages into the `outbox` table." An adopter cannot tell which columns are required, which
have defaults, what `topic` must look like to match a route, or whether `headers` may be null.

**Fix:** Write `docs/integration.md`. It must contain:
1. The required and optional columns for an outbox insert, with defaults, taken from the migration.
2. A worked `INSERT` for PostgreSQL and for SQL Server, inside an application transaction.
3. The same in one ORM per language the project claims to support, or an explicit statement that
   only raw SQL is documented.
4. The rule that the insert must share the transaction of the business write. Explain why: that
   shared transaction is the entire point of the outbox pattern.
5. The reading side for the inbox, and the statement that QueueBox forwards inbox rows itself
   (decision 1 in section 2A), so an application consumes the forwarded message at the destination,
   not from the inbox table.

**DoD:** Every SQL statement in `docs/integration.md` is executed by a test against the shipped
schema, and the inserted row is delivered end to end.

### F-085 — The delivery guarantees are never stated

**Severity:** Major
**File:** `README.md`

The README says "guaranteeing delivery" and never defines the guarantee. There is no statement of
at-least-once versus at-most-once, no statement of where ordering holds, and no statement of what
survives a crash. Adopters of an infrastructure component must know these before they can use it.

**Fix:** Add a "Guarantees" section to the README stating, as short declarative sentences:
- Delivery is at-least-once. A destination must be idempotent. Explain that QueueBox sends
  `X-Message-Id` for that purpose.
- Ordering: state exactly what holds after F-009 and F-014. Concurrency inside a batch removes
  ordering between messages, so say so.
- Crash behaviour: a claimed message returns to `pending` after the claim timeout (F-006), which can
  produce a duplicate delivery.
- Deduplication: the inbox deduplicates on `(source, idempotency_key)`, and the window is the
  retention period. State that retention shorter than the source's retry window reopens duplicates.
- Transform errors: what each of `fail`, `skip`, and `dead` does.

**DoD:** Each stated guarantee names the test that proves it, and every named test exists and
passes.

---

## 10. Cross-cutting test plan

These suites must exist when the work is complete. They are the evidence for section 11.

| Suite | Location | Covers |
|-------|----------|--------|
| `InboxRepositoryConcurrencyTest` | `postgres`, `sqlserver` | F-001 |
| `OutboxRepositoryConcurrencyTest` | `postgres`, `sqlserver` | F-009 |
| `StaleClaimReclaimTest` | `postgres`, `sqlserver` | F-006 |
| `RetentionServiceIntegrationTest` | `outbox-service` | F-007, F-008, F-033 |
| `TransformEngineConcurrencyTest` | `outbox-service` | F-010 |
| `ConfigValidatorSecurityTest` | `config` | F-011, F-038, F-040, F-057 |
| `InboxAuthValidatorSecurityTest` | `inbox-service` | F-035, F-036, F-037 |
| `AdminRoutesAuthTest` | `app` | F-034 |
| `RequestLimitsTest` | `app` | F-023, F-024 |
| `GracefulShutdownTest` | `app` | F-027, F-028, F-029 |
| `E2ERabbitMQDestinationTest` | `app` | F-003, F-004, F-020, F-022 |
| `E2EInboxRelayTest` | `app` | F-002 |
| `DocumentedExamplesTest` | `app` | Every YAML and curl example in every document |
| `MigrationTest` | `postgres`, `sqlserver` | F-030, F-031 |
| `IntegrationContractTest` | `app` | F-084, every SQL statement in `docs/integration.md` |
| `GuaranteesTest` | `app` | F-085, one test per stated guarantee |

**`DocumentedExamplesTest` is mandatory.** Extract every fenced `yaml` block from `README.md` and
`docs/**/*.md`, load each through `ConfigLoader`, and assert it validates. This mechanism prevents
the documentation and the code from drifting again, which is the root cause of F-003 to F-005 and
F-073 to F-079.

---

## 11. Definition of Done for the whole effort

The work is complete when every item below is true and each has recorded evidence in
`docs/build/STATUS.md`.

1. `./gradlew clean build check jacocoAggregatedReport` passes on a clean checkout with Docker
   available, on Java 21.
2. Aggregate coverage is at or above 80 percent line and 70 percent branch. No module is below 60
   percent.
3. `grep -rn "println(" --include='*.kt' */src/main` returns zero results.
4. A grep for `TODO`, `FIXME`, and `Hello, Kotlin` across `*.kt` returns zero results.
5. `./gradlew ktlintCheck detekt` passes.
6. Every finding F-001 to F-085 is either closed with evidence, or recorded in
   `docs/build/STATUS.md` as explicitly deferred with a written rationale and a target version. A
   deferral requires the maintainer's decision; the implementing agent must not defer a blocker.
7. `docker compose up -d` produces a healthy instance that accepts an inbox POST and delivers an
   outbox row to an HTTP destination and to a RabbitMQ destination. Proven by a CI job.
8. Every fenced YAML configuration block in the documentation loads and validates, asserted by
   `DocumentedExamplesTest`.
9. Every metric documented in the README is present in a live `/metrics` scrape, asserted by a test.
10. Every HTTP status code documented for every endpoint is produced by a test.
11. `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, `CODEOWNERS`,
    issue templates, and a pull request template exist.
12. CI runs build, test, lint, coverage, image build, and image scan on every pull request, and all
    are green on the default branch.
13. A tagged release publishes a container image and an SBOM.
14. `git ls-files` contains no agent tooling directories.
15. A fresh reader can go from `git clone` to a delivered message in under 10 minutes using only
    `README.md`. Verify by following the document literally and recording the transcript in
    `docs/build/STATUS.md`.

**Adversarial review gate.** After all of the above, an independent model reviews the repository
against this document with the instruction: "find any claim in the documentation that the code does
not perform, any message path that can lose or duplicate a message, and any credential that can reach
a log". The gate passes when that review produces no confirmed finding. A finding the reviewer cannot
reproduce with a failing test is not blocking.

---

## 12. Findings index

| ID | Severity | Area | Summary |
|----|----------|------|---------|
| F-001 | Blocker | Data | Inbox claim takes no row locks; duplicate processing across replicas |
| F-002 | Blocker | Feature | No inbox relay; stored messages never processed |
| F-003 | Blocker | Feature | RabbitMQ publisher never registered; messages dead-lettered |
| F-004 | Blocker | Feature | Route routing keys computed and discarded |
| F-005 | Blocker | Docs | Routing key template syntax does not match the implementation |
| F-006 | Blocker | Data | No recovery of messages stuck in `processing` |
| F-007 | Blocker | Data | Inbox age retention deletes nothing |
| F-008 | Blocker | Data | Retention batching is not batched |
| F-009 | Blocker | Data | Outbox claim has no ordering and no `SKIP LOCKED` |
| F-010 | Blocker | Concurrency | `computeIfAbsent` mutates its own map |
| F-011 | Blocker | Security | Table names interpolated into SQL without validation |
| F-012 | Blocker | Legal | No LICENSE file |
| F-013 | Major | Correctness | One failure aborts the rest of the batch |
| F-014 | Major | Performance | Strictly sequential message processing |
| F-015 | Major | Performance | Pending count query on every poll |
| F-016 | Major | Operability | Publish errors discarded; no error column |
| F-017 | Major | Correctness | Two methods increment `attempt` |
| F-018 | Major | Concurrency | Concurrent acknowledgements on one AMQP channel |
| F-019 | Major | Correctness | Consumer stop cancels before acknowledgement |
| F-020 | Major | Performance | Channel and exchange churn per published message |
| F-021 | Major | Performance | Synchronous publisher confirms |
| F-022 | Major | Correctness | `mandatory` set with no return listener |
| F-023 | Major | Security | No request body size limit |
| F-024 | Major | Security | No rate limiting on the inbox |
| F-025 | Major | Performance | Payload re-serialised for each extraction |
| F-026 | Major | Performance | Topic regular expressions recompiled and unvalidated |
| F-027 | Major | Operability | Retention shutdown blocks for the cleanup interval |
| F-028 | Major | Operability | Poller shutdown wait is unbounded |
| F-029 | Major | Operability | HTTP server never stopped on shutdown |
| F-030 | Major | Operability | Migrations exist but are never applied |
| F-031 | Major | Operability | Migration numbering diverges between providers |
| F-032 | Major | Correctness | PostgreSQL identifiers are not quoted |
| F-033 | Major | Docs | Retention age column differs per table and is undocumented |
| F-034 | Blocker | Security | Unauthenticated admin transform endpoint |
| F-035 | Major | Security | HMAC replay protection does not sign the timestamp |
| F-036 | Major | Security | Bearer token accepted without the scheme |
| F-037 | Minor | Security | `secureCompare` is not the recommended primitive |
| F-038 | Major | Security | Secrets printed by data class `toString` |
| F-039 | Major | Security | Unbounded error body capture |
| F-040 | Major | Security | No destination URL validation |
| F-041 | Minor | Security | No TLS guidance |
| F-042 | Minor | Security | Non-LTS JRE in the image |
| F-043 | Major | Security | No dependency scanning or SBOM |
| F-044 | Major | Security | No image scanning or pinning |
| F-045 | Minor | Security | No secret manager story |
| F-046 | Blocker | Observability | No logging framework; `println` throughout |
| F-047 | Major | Observability | No correlation identifier |
| F-048 | Minor | Observability | No tracing |
| F-049 | Major | Operability | Liveness and readiness not separated |
| F-050 | Major | Operability | Health ignores the broker and the workers |
| F-051 | Minor | Security | Metrics on the public port |
| F-052 | Minor | Observability | Metric gaps |
| F-053 | Minor | Observability | Hard-coded version tag |
| F-054 | Major | Docs | No operations runbook |
| F-055 | Major | Operability | No dead-letter inspection or replay path |
| F-056 | Minor | Operability | No startup retry for the database |
| F-057 | Major | Operability | Transform expressions not validated at startup |
| F-058 | Governance | Legal | Missing LICENSE (see F-012) |
| F-059 | Governance | CI | No continuous integration |
| F-060 | Governance | Security | No SECURITY.md |
| F-061 | Governance | Community | No contributing, conduct, owners, or templates |
| F-062 | Governance | Build | Java 23 toolchain versus documented Java 21 |
| F-063 | Governance | Release | No version, no published artifact |
| F-064 | Governance | Release | No changelog, no release process |
| F-065 | Governance | Hygiene | Agent tooling committed |
| F-066 | Governance | Quality | No lint or format enforcement |
| F-067 | Governance | Build | Versions declared outside the catalog |
| F-068 | Governance | Build | No dependency locking |
| F-069 | Governance | Quality | Coverage gates too low |
| F-070 | Governance | Quality | Coverage exclusions hide real code |
| F-071 | Governance | Hygiene | Template `Main.kt` files and the `utils` module |
| F-072 | Minor | Docs | Empty `docs/`; monolithic README |
| F-073 | Major | Docs | `.env.example` describes AI keys |
| F-074 | Blocker | Docs | Compose sets variables the application ignores |
| F-075 | Major | Docs | Manual setup instructions do not work |
| F-076 | Major | Operability | Configuration lives inside the jar |
| F-077 | Major | Docs | Documented states and column widths are wrong |
| F-078 | Minor | Docs | Inbox response codes undocumented and non-standard |
| F-079 | Minor | Docs | Metrics table incomplete |
| F-080 | Minor | Docs | No architecture documentation |
| F-081 | Minor | Docs | No runnable examples |
| F-082 | Minor | Docs | Roadmap makes unqualified promises |
| F-083 | Minor | Docs | No badges or support statement |
| F-084 | Major | Docs | The integration contract for the outbox insert is not documented |
| F-085 | Major | Docs | The delivery guarantees are never stated |

---

## Appendix A — Prompt for the implementing session

Use this prompt verbatim to start each implementation session. It is self-contained.

```
Continue the autonomous QueueBox hardening implementation.

Read these first, in this order:
1. hardening-doc.md
2. docs/build/STATUS.md (create it from the phase table in hardening-doc.md if absent)
3. The source files named by the findings in the current phase.

Treat hardening-doc.md as immutable and authoritative. Do not redesign the product.
Do not add features that are not listed in it.

Determine the first incomplete phase from docs/build/STATUS.md.

For that phase:
1. Use the writing-plans skill to create or validate docs/build/phase-XX-plan.md.
   The plan must list every finding ID in the phase, its files, and its Definition of Done.
2. For each finding, in order:
   a. Write the test named in the Definition of Done. Run it. Confirm it fails for the
      stated reason.
   b. Implement the fix.
   c. Run the test. Confirm it passes.
   d. Run ./gradlew check. Confirm no regression.
3. Use subagent-driven development for independent findings within the phase.
4. Run the full verification: ./gradlew clean build check jacocoAggregatedReport
5. Complete a spec-compliance review against the Definition of Done of every finding
   in the phase. Fix only verified blocking defects.
6. Run one independent audit pass looking for concrete violations of hardening-doc.md
   and correctness defects only. Validate each finding yourself before acting on it.
   Fix only the verified blocking ones. Rerun the affected tests.
7. Confirm the phase exit condition from section 3 of hardening-doc.md with command
   output as evidence.
8. Commit the phase. Reference every finding ID in the commit body.
9. Update docs/build/STATUS.md with: the phase completed, the evidence commands and
   their results, the findings closed, any finding deferred with its rationale, and
   the next phase to start.

Do not start a review loop. Do not ask whether to continue.

Stop only for:
- a genuinely unresolved product decision. Section 2A of hardening-doc.md records
  every decision the maintainer has already made. Do not reopen those;
- an unresolvable conflict between hardening-doc.md and the code;
- credentials or account setup that only the maintainer can provide, for example the
  Maven Central signing key or the GHCR permissions in F-063;
- an external blocker.

When you stop, write the exact question into docs/build/STATUS.md and end the session.

Otherwise continue until the current phase is completely verified.

If all six phases are complete, run the full Definition of Done in section 11 of
hardening-doc.md, including the adversarial review gate, and record the evidence.
```

---

## Appendix B — Open questions

None. Every product decision this review raised is settled. See section 2A.

The implementing agent must still stop for a decision that this document does not cover, for an
unresolvable conflict between this document and the code, for credentials that only the maintainer
can provide (the GHCR permissions in F-063), or for an external blocker. When it stops, it writes
the exact question into `docs/build/STATUS.md` and ends the session.
