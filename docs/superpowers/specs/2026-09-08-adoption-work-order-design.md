# QueueBox Adoption Work Order

**Version:** 1.0
**Date:** 2026-09-08
**Scope:** The defects and the gaps that a first external adopter reported after a migration from
Debezium. The review covers the RabbitMQ inbox consumer, the outbox destination model, the metrics,
the admin surface, and the `QueueBox.Inbox` client library for .NET.
**Predecessor:** `hardening-doc.md`, findings `F-001` to `F-085`. This document continues the
numbering at `F-086` and keeps the same rules.

---

## 0. How to use this document

This document is the authoritative work order for the adoption work. Treat sections 3 to 8 as
immutable requirements.

**Rules for the implementing agent:**

1. Do not redesign the product. Do not add a feature that this document does not list.
2. Work through the phases in order. Phase N+1 must not start before the exit condition of
   Phase N is met with evidence.
3. Every finding has an ID. Reference the ID in the commit message.
4. Every finding has a **Definition of Done (DoD)**. The DoD is a command or a test that produces
   evidence. A finding is not done until the evidence exists.
5. Write the test first. Confirm that the test fails. Then write the fix. Then confirm that the
   test passes.
6. After each phase, update `docs/build/STATUS.md` with the durable state.
7. Do not mark a finding as done because the code looks correct. Run the command.
8. Section 2 records the product decisions that the maintainer already made. They are closed.

**Verification baseline used for this review:**

Every finding below comes from a read of the source at commit `2ea2589`. The file and the line
number in each finding were confirmed against that commit. No finding claims that a test currently
fails, because the suite did not run during the review.

---

## 1. Executive summary

An external team migrated a service from Debezium to QueueBox. The team reported fourteen items.
Ten are real. One is already closed. Two are matters for the documentation of the adopter. Two are
out of scope, and this document records the reason for each.

Three classes of problem block a comfortable adoption:

1. **A hot loop on a poison message.** The RabbitMQ consumer requeues a body that never parses.
   The Kafka consumer and the NATS consumer already store such a body dead. Only RabbitMQ spins.
2. **A pull client that is unsafe by default.** The claim query ignores `aggregate_id`, and ten
   handlers run at one time. Two sibling messages of one aggregate meet inside one process, and a
   handler that reads a row and then writes it produces a duplicate key error.
3. **A destination that a row cannot choose.** The exchange is a fixed string per route, so a
   service with ten aggregate types needs ten destinations and ten routes. Debezium derives the
   topic from a column and needs neither.

Counts: 3 blocker findings, 9 major findings, 3 minor findings.

---

## 2. Settled product decisions

The maintainer made these decisions on 2026-09-08. They are authoritative and closed. The
implementing agent must not reopen them, must not offer an alternative, and must not ask about
them.

| # | Subject | Decision |
|---|---------|----------|
| 1 | Poison message | A body that fails before the inbox row exists becomes one row in state `dead`, and the delivery is acknowledged once. No retry. No requeue. |
| 2 | Pull aggregate reservation | The claim excludes an aggregate that already holds a row in state `processing`. The rule lives in SQL, so it holds across every worker instance. |
| 3 | `MaxConcurrency` | The default becomes 1. The change is breaking, and `CHANGELOG.md` records it. |
| 4 | Destination selection | The exchange name renders from a template, and a destination can also read the exchange from a row column. Both exist. |
| 5 | Aggregate type | A migration adds a nullable `aggregate_type` column to the outbox table. |
| 6 | Replay | An authenticated admin endpoint returns a selected set of sent rows to state `pending`. |
| 7 | Source queue | QueueBox never declares a source queue by default. The error names the cause and the fix, and an opt-in setting declares the queue. |
| 8 | .NET plumbing | The dependency injection helpers ship in a second package, `QueueBox.Inbox.DependencyInjection`. |
| 9 | `LISTEN` and `NOTIFY` | Rejected for this work order. Section 8 records the reason. |
| 10 | Change capture on arbitrary tables | Refused. Section 8 records the reason, and the README states the scope in one sentence. |

### Correction to the report

The adopter attributed the load to a 200 millisecond poll and to 501,234 connections. Both numbers
need a correction, and the correction belongs in the answer to the adopter.

- The default poll interval is 100 milliseconds. See `config/src/main/kotlin/QueueBoxConfig.kt:153`.
- The figure of 501,234 counts pool acquisitions, not connections. The pool holds a small fixed set
  of connections and hands one out per poll.

The latency argument survives both corrections. The load argument does not.

---

## 3. Phase plan

| Phase | Title | Findings | Exit condition |
|-------|-------|----------|----------------|
| 7 | Stop the bleeding | F-086 to F-089 | A body that is not JSON produces one dead row and one acknowledgement. Two pull workers never hold two messages of one aggregate at one time. `MaxConcurrency` defaults to 1. |
| 8 | The database contract | F-090 to F-093 | The outbox table carries `aggregate_type`. An exchange renders per row. A bad template fails the startup. The documented insert shows `headers` and `aggregate_type`. |
| 9 | Operations | F-094 to F-097 | Both age gauges report seconds. `POST /admin/replay` returns rows to pending. The queue error names its fix. |
| 10 | Client packages and words | F-098 to F-100 | The second NuGet package publishes. The three attribute header names come from the configuration. The ordering guarantee is stated per mode, and a test covers each sentence. |

---

## 4. Phase 7 — Stop the bleeding (Blockers)

### F-086 — The RabbitMQ consumer requeues a body that never parses

**Severity:** Blocker
**File:** `rabbitmq/src/main/kotlin/RabbitConsumer.kt:179` and `:261`

`processMessage` calls `json.parseToJsonElement` as its first statement. A body that is not JSON
throws, and the catch-all at line 261 calls `sendNack(deliveryTag, requeue = true)`. The broker
returns the same message at once, and the loop repeats at full speed. There is no backoff, no
attempt limit, and no dead path on that route. An operator must stop the container and purge the
queue by hand.

The two sibling consumers already solve this. `KafkaInboxConsumer.kt:169` calls `storeUnparsable`
when `parsePayload` returns null. `NatsInboxConsumer.kt:252` documents the same behaviour. Only the
RabbitMQ consumer lacks the path.

**Fix:** Add a `storeUnparsable` function to `RabbitConsumer`, in the shape that
`KafkaInboxConsumer` already uses. Parse the body through a function that returns null instead of a
throw. On null, store one row in state `dead` in ONE transaction, carrying the raw body and the
sanitised reason, then acknowledge the delivery. Reuse the existing `storeRejected` transaction,
because it already writes state `dead` in one statement and never leaves a claimable row.

Keep the requeue for a genuine storage failure. A failure to reach the database is transient, and a
body that is not JSON is not.

**DoD:**

- An integration test publishes the body `not json` to a source queue.
- The test asserts exactly one inbox row, in state `dead`, whose payload holds the raw bytes.
- The test asserts that the queue holds zero messages after one delivery.
- The test asserts that the consumer performed exactly one delivery for that message.

---

### F-087 — The pull claim ignores `aggregate_id`

**Severity:** Blocker
**File:** `clients/csharp/src/QueueBox.Inbox/InboxSql.cs`

The claim statement carries no predicate on `aggregate_id`. `InboxSchema.cs:61` exposes the column
name, so the library knows the column exists, and it uses the column for nothing. The index
`idx_inbox_aggregate_state` on `(aggregate_id, state)` already exists, and the push relay already
respects the column.

**Impact:** Four messages of one aggregate run in parallel in an arbitrary order. Every consumer
must write an atomic upsert to stay correct, and a handler that reads a row and then writes it
fails with a duplicate key error.

**Fix:** Add the predicate that the push relay uses. A candidate row qualifies when its
`aggregate_id` is null, or when no row of the same `aggregate_id` holds state `processing`. Write
the predicate for both dialects, PostgreSQL and SQL Server. Keep the statement one round trip.

State the resulting guarantee in a comment above the statement, and in the documentation of the
source.

**DoD:**

- A test starts two workers against one database, with four messages of one aggregate.
- The handler records its entry time and its exit time.
- The test asserts that no two handler windows overlap.
- A second test asserts that two messages of two different aggregates DO run at one time, so the
  fix does not serialise everything.

---

### F-088 — `MaxConcurrency` defaults to the batch size

**Severity:** Blocker
**File:** `clients/csharp/src/QueueBox.Inbox/InboxOptions.cs:19` and `:40`

`BatchSize` is 10 and `MaxConcurrency` is null. `EffectiveConcurrency` reads
`Math.Min(MaxConcurrency ?? BatchSize, BatchSize)`, so ten handlers run at one time. Nothing in the
name of the type hints at that, and a handler that looks correct fails only when it meets a sibling
message.

**Fix:** Change the default to 1. Keep the property nullable, and let null mean 1. State the
default and the reason in the XML documentation of the property, and in the README of the client.
Record the change in `CHANGELOG.md` under breaking changes, with the one line that a consumer needs
to restore the old behaviour.

**DoD:**

- A unit test asserts that `new InboxOptions { Source = "s" }.EffectiveConcurrency` is 1.
- A unit test asserts that an explicit `MaxConcurrency = 5` still yields 5.
- `CHANGELOG.md` carries the entry under breaking changes.

---

### F-089 — The ordering guarantee is undocumented

**Severity:** Major
**File:** `docs/delivery-semantics.md`

The document never uses the word `aggregate`. The adopter learned by experiment that the column
drives order in push mode and was metadata in pull mode. A guarantee that an adopter must discover
by experiment is not a guarantee.

**Fix:** Add one section that states, in plain sentences:

1. QueueBox delivers at least once.
2. In push mode, one aggregate holds at most one message in flight, so the relay preserves the
   order of that aggregate.
3. In pull mode, the same rule holds after F-087, and it holds across every worker instance.
4. QueueBox preserves no order between two different aggregates.
5. A poller delivers in claim order, not in commit order. A row can take its identifier before
   another row and still commit after it, so a reader that needs commit order must not rely on the
   identifier.

Point 5 is the one that a team from Debezium needs, because Debezium delivers in commit order and a
poller does not get that for free.

**DoD:** Each of the five sentences has a test, and the test names the sentence it proves. Follow
the rule that section 11 of `hardening-doc.md` already sets.

---

## 5. Phase 8 — The database contract

### F-090 — The outbox table has no `aggregate_type` column

**Severity:** Major
**File:** the migration directory of each dialect

A templated exchange needs a field to render from. Debezium reads `aggregatetype`, and that is how
a topic comes out as `public.orchestration-engine.Task.v1` with no configuration per event type.

**Fix:** Add a migration that adds a nullable `aggregate_type` column to the outbox table, for
every supported dialect. Add the index that the claim and the render need. The column is nullable,
so the migration is additive and no existing writer breaks.

**DoD:** The migration applies to an empty database and to a populated database. A test asserts
that a row with a null `aggregate_type` still publishes.

---

### F-091 — The destination exchange is a fixed string

**Severity:** Major
**File:** `core/src/main/kotlin/Destination.kt:86`

`Destination.RabbitMQ` takes `exchange: String`. Only `routingKeyTemplate` renders. A service with
ten aggregate types needs ten destinations and ten routes, and every new aggregate type needs a
configuration change and a deployment.

**Fix:** Two mechanisms, and a row uses at most one.

1. `exchange` accepts a template, and it renders through the existing `RoutingKeyRenderer`. The
   fields are the fields that the routing key template already offers, plus `aggregateType`.
2. `exchangeFrom` names a column of the outbox row. When it is present, the value of that column is
   the exchange, and it overrides the template.

A rendered exchange that is empty, or that holds a character which RabbitMQ refuses, fails the row
through the normal retry path. It must never publish to a guessed name, and it must never fall back
to a default exchange.

Apply the same treatment to the Kafka `topic` and to the NATS subject, because the same argument
holds for both.

**DoD:**

- A test publishes two rows of two aggregate types through ONE destination, and asserts two
  exchanges.
- A test with `exchangeFrom` set asserts that the column value wins over the template.
- A test asserts that an empty rendered exchange fails the row and publishes nothing.

---

### F-092 — A bad exchange template fails at the first message

**Severity:** Minor
**File:** `outbox-service/src/main/kotlin/StartupValidator.kt`

A template that names a field which does not exist must not reach production. The validator already
checks other configuration at startup.

**Fix:** Validate every exchange template, topic template and subject template at startup. Name the
unknown field and name the destination in the error.

**DoD:** A test starts the application with the template `{{ nosuchfield }}` and asserts that the
startup fails with an error that names the field.

---

### F-093 — The documented insert omits `headers` and `aggregate_type`

**Severity:** Major
**File:** `docs/integration.md`

The outbox table already carries `headers jsonb not null default '{}'`, so per-message headers need
no new feature. The adopter did not find it, and their entity did not map it. That is the header
contract on which their consumers depend, such as `aggregateId` and `eventType`.

**Fix:** Give the outbox insert its own worked example per supported database, showing the insert
inside the application transaction, with `headers` and `aggregate_type` populated. Add the Entity
Framework Core mapping for both columns, because a .NET adopter maps an entity and not a table.

**DoD:** Every code sample runs under the document test harness that Phase 6 already built.

---

## 6. Phase 9 — Operations

### F-094 — The outbox lag metric is a row count

**Severity:** Major
**File:** `core/src/main/kotlin/metrics/QueueBoxMetrics.kt:41`

`queuebox_outbox_messages_pending` is a gauge of rows. A value of five cannot tell an operator
whether the relay is busy or dead, and it is the metric that everybody alerts on. The Debezium
equivalent is a time behind the source.

**Fix:** Add `queuebox_outbox_oldest_pending_age_seconds`. The value is the age of the oldest row
in state `pending`. The value is zero when no row is pending. Keep the count gauge, because the two
answer different questions.

**DoD:** A test inserts a row with a known `created_at`, scrapes the endpoint, and asserts the age
within a tolerance. A second test asserts zero on an empty table.

---

### F-095 — The inbox has no lag metric at all

**Severity:** Major
**File:** `core/src/main/kotlin/metrics/QueueBoxMetrics.kt`

**Fix:** Add `queuebox_inbox_oldest_pending_age_seconds`, with the same contract as F-094.

**DoD:** As F-094, against the inbox table.

---

### F-096 — No replay of a range of sent rows

**Severity:** Major
**File:** `app/src/main/kotlin/AdminRoutes.kt:46`

The admin surface holds one route, `/transform/test`. RabbitMQ keeps no log, so the outbox table is
the only place a resend can come from, and retention deletes a row after seven days. A downstream
service that loses data has no way back.

**Fix:** Add `POST /admin/replay`, behind the existing admin guard. The body selects rows by a time
range, by source, by destination, or by an explicit list of identifiers. The endpoint returns the
count of rows that moved back to state `pending`.

Three rules bind the endpoint:

1. A request with no filter is refused. A replay of everything is never an accident.
2. Only a row in state `sent` or `dead` moves. A row in `pending` or `processing` is left alone,
   because the relay owns it.
3. The response reports the count before the operator can lose it, and the log records the filter.

**DoD:**

- A test replays a time range and asserts the count and the new states.
- A test asserts that a request with no filter answers 400.
- A test asserts that a row in state `processing` does not move.
- A test asserts that an unauthenticated request answers 401.

---

### F-097 — The queue-not-found error names no fix

**Severity:** Minor
**File:** `rabbitmq/src/main/kotlin/RabbitConsumer.kt` and
`rabbitmq/src/main/kotlin/RabbitPublisher.kt:136`

`NOT_FOUND - no queue 'x'` does not say that QueueBox never declares a source queue. The asymmetry
surprises an operator, because the publisher DOES declare the destination exchange.

**Fix:** Two parts.

1. Catch the failure and raise an error that states three things: the queue does not exist,
   QueueBox does not declare a source queue by default, and the setting that changes that.
2. Add a source setting `declareQueue`, default false. When it is true, QueueBox declares a durable
   queue with the configured name before it consumes.

The default stays false, because a declaration can mask a typo by creating an empty queue that
never receives a message.

**DoD:** A test against a broker with no such queue asserts the three parts of the message. A
second test with `declareQueue: true` asserts that the queue exists after the startup.

---

## 7. Phase 10 — Client packages and words

### F-098 — The .NET package ships no dependency injection registration

**Severity:** Major
**File:** `clients/csharp/src/QueueBox.Inbox/`

No file in the package references `IServiceCollection`. Every consumer writes the registration
again, and a consumer who writes it alone can hit the `AddHostedService` trap, where a second
worker disappears in silence.

**Fix:** Add a second package, `QueueBox.Inbox.DependencyInjection`, that ships
`AddQueueBoxInbox`. The extension registers the options, the connection source, and exactly one
hosted service per named worker. A second registration of the same name fails loudly rather than
in silence.

The core package keeps its single dependency on `Microsoft.Extensions.Logging.Abstractions`, so a
consumer with no container pays nothing.

**DoD:** A test registers two named workers and asserts that both run. A test asserts that a
duplicate name throws. `dotnet list package` on the core package shows the single dependency.

---

### F-099 — No helper builds a database context on the transaction of the message

**Severity:** Major
**File:** the new `QueueBox.Inbox.DependencyInjection` package

A handler must write inside the transaction that the worker already opened, or the inbox guarantee
is lost. Every consumer writes that plumbing again.

**Fix:** Ship the helper that builds an Entity Framework Core context on the connection and the
transaction of the message. Document the one correct usage in the README of the client, and
document what happens when a handler opens its own transaction instead.

**DoD:** A test writes a row through the helper, throws from the handler, and asserts that the
write rolled back with the message.

---

### F-100 — The three inbox attribute header names are hardcoded

**Severity:** Minor
**File:** `rabbitmq/src/main/kotlin/RabbitConsumer.kt:359`,
`kafka/src/main/kotlin/KafkaInboxConsumer.kt:331`,
`nats/src/main/kotlin/NatsInboxConsumer.kt:296`

QueueBox reads only `x-idempotency-key`, `x-aggregate-id` and `x-event-type`. A real Debezium
producer sends `id`, `eventType` and `aggregateId`. The adopter escaped through JSONPath, because
the payload was a CloudEvent. A flat payload with good headers needs a code change today.

**Fix:** Add a source setting that maps the three attribute names. The current names stay the
default, so no existing deployment changes. Apply the setting in all three consumers, and keep the
existing fallback chain below it.

**DoD:** A test configures the names `id`, `eventType` and `aggregateId`, publishes a message with
those headers and no payload field, and asserts the three columns of the stored row.

---

## 8. Rejected and refused

### Rejected for this work order: `LISTEN` and `NOTIFY`

A trigger on the outbox table that calls `NOTIFY`, plus a `LISTEN` in the relay and a slow poll as
the fallback, gives near-stream latency with no replication slot.

The reason to reject it now:

1. The load argument rests on two wrong numbers. See the correction in section 2.
2. The feature is PostgreSQL-only, and QueueBox supports SQL Server as well.
3. It adds a second code path to the hottest loop in the product, and it needs a trigger that the
   application must install.

The latency argument is real. Reopen the option after the ten findings above are closed, and after
a measurement states the latency that an adopter actually needs.

### Refused: change capture on arbitrary business tables

QueueBox requires the application to write an outbox row, so it replaces Debezium for the outbox
pattern only. A team that uses Debezium to mirror a table it does not own needs a different
product. To chase that team costs the simplicity that makes this tool worth using.

**Action:** State the scope in one sentence in the README, beside the existing product statement.
No code.

### Already closed: publish to NuGet

`.github/workflows/release-clients.yml` pushes `QueueBox.Inbox` to nuget.org through trusted
publishing. Commits `262a344` and `2ea2589` closed it. The remaining part of the report is F-098
and F-099.

### A matter for the adopter: the unmapped `headers` column

The `headers` column exists. The entity of the adopter does not map it. The QueueBox action is
F-093, the documented example. No code change in QueueBox.

---

## 9. Preserved behaviour

The implementing agent must not trade these away.

1. **The lease protocol.** `claim_token`, `lease_expires_at` and the reclaim counter give a better
   failure story than one connector task per replication slot. Every change to the claim path must
   keep the token fence.
2. **The absence of a replication slot.** The worst Debezium hazard is a dead connector that holds
   write-ahead log until the primary fills its disk. QueueBox has no such failure, and no finding
   above introduces one.
3. **The dead path writes one row in one transaction.** `hardening-doc.md` records the defect that
   a store followed by a mark-dead produced. F-086 must reuse the single-transaction path, not
   rebuild it.
