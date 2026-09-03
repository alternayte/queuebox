# Phase 1 — Truth in advertising: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Make every advertised QueueBox behaviour real, and close finding F-001 to F-012.

**Architecture:** The message path keeps its current shape. The claim step becomes a single
atomic statement per database. A `claimed_at` column plus a `reclaimStale` call recovers crashed
claims. A new `InboxRelay` in `inbox-service` moves inbox rows into the outbox table. The
publisher interface gains a `PublishContext` so the route routing key reaches `RabbitPublisher`.

**Tech Stack:** Kotlin, Gradle, Exposed, Ktor, coroutines, PostgreSQL, SQL Server, RabbitMQ,
Testcontainers, JUnit 5, JaCoCo.

**Spec:** `hardening-doc.md`, section 4.

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed. No handler plug-in point. Container image only.
- Write the test first. Confirm the failure. Then write the fix.
- Every finding closes with command output as evidence.
- `./gradlew check` must pass after every finding.

## File structure

| File | Responsibility |
|------|----------------|
| `core/src/main/kotlin/Publisher.kt` | Publisher interface and `PublishContext`. |
| `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt` | Claim, retry, reclaim, delete with limit. |
| `core/src/main/kotlin/repository/InboxRepositoryInterface.kt` | Claim, mark, reclaim, delete with limit. |
| `postgres/src/main/kotlin/OutboxRepository.kt` | PostgreSQL outbox SQL. |
| `postgres/src/main/kotlin/InboxRepository.kt` | PostgreSQL inbox SQL. |
| `postgres/src/main/kotlin/Tables.kt`, `DynamicTables.kt` | Exposed table definitions, plus `claimed_at`. |
| `sqlserver/src/main/kotlin/org/nxtspec/SqlServer*Repository.kt` | SQL Server equivalents. |
| `postgres/src/main/resources/db/migration/V3__add_claimed_at.sql` | PostgreSQL migration. |
| `sqlserver/src/main/resources/db/migration/V2__add_claimed_at.sql` | SQL Server migration. |
| `outbox-service/src/main/kotlin/RetentionService.kt` | Per-table state list, batched deletes. |
| `outbox-service/src/main/kotlin/OutboxPoller.kt` | Passes `PublishContext`, calls `reclaimStale`. |
| `outbox-service/src/main/kotlin/transform/TransformEngine.kt` | Bounded LRU expression cache. |
| `inbox-service/src/main/kotlin/InboxRelay.kt` | New. Moves inbox rows to the outbox table. |
| `rabbitmq/src/main/kotlin/RabbitPublisher.kt` | Uses the routing key from the context. |
| `app/src/main/kotlin/App.kt` | Registers `RabbitPublisher` and `InboxRelay`. Startup validation. |
| `config/src/main/kotlin/ConfigValidator.kt` | Table name identifier validation. |
| `LICENSE` | MIT text. |

---

### Task 1: F-012 — LICENSE file

**Files:** Create `LICENSE`. Modify `README.md`.

- [ ] **Step 1:** Write the full MIT text into `LICENSE`, copyright holder `Nathan Anderson-Tennant`, year 2026.
- [ ] **Step 2:** Link `LICENSE` from the README licence section.
- [ ] **Step 3:** Evidence: `head -3 LICENSE` prints `MIT License`.

**DoD:** `LICENSE` exists at the repository root and the README links to it.

---

### Task 2: F-010 — Bounded LRU expression cache

**Files:** Modify `outbox-service/src/main/kotlin/transform/TransformEngine.kt`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/TransformEngineCacheTest.kt`.

- [ ] **Step 1:** Write `TransformEngineCacheTest`. It runs 8 threads for 5 seconds, each
      evaluating a distinct expression, inside `assertTimeoutPreemptively(Duration.ofSeconds(30))`,
      then asserts `engine.cacheSize() <= maxCacheSize` and that no exception escaped.
- [ ] **Step 2:** Run `./gradlew :outbox-service:test --tests '*TransformEngineCacheTest*'`.
      Expected: FAIL, because `computeIfAbsent` mutates the map and the size can exceed the bound.
- [ ] **Step 3:** Replace `expressionCache` with a synchronised `LinkedHashMap` in access order
      whose `removeEldestEntry` returns `size > maxCacheSize`. Do not modify the map from inside a
      mapping function.
- [ ] **Step 4:** Run the test. Expected: PASS.
- [ ] **Step 5:** Run `./gradlew check`.

**DoD:** The test passes and `cacheSize() <= maxCacheSize`.

---

### Task 3: F-005 — Routing key template syntax

**Files:** Modify `README.md`. Test:
`outbox-service/src/test/kotlin/org/nxtspec/RoutingKeyTemplateContractTest.kt`.

Decision: keep the explicit `payload.` and `data.` prefixes. Correct the README.

- [ ] **Step 1:** Write a table-driven `RoutingKeyTemplateContractTest` that enumerates every
      placeholder form the corrected README shows: `{{ topic }}`, `{{topic}}`,
      `{{ payload.region }}`, `{{ data.customer.region }}`, a missing field with the default,
      and an unknown bare name resolving to the default.
- [ ] **Step 2:** Run it. Expected: FAIL for the forms the README claims but the code does not
      support, until the README is the one that changes.
- [ ] **Step 3:** Correct the README "Routing Key Templates" section to document only `topic`,
      `payload.*`, and `data.*`, and name `RoutingKeyTemplateContractTest` as the source of truth.
- [ ] **Step 4:** Run the test. Expected: PASS.
- [ ] **Step 5:** Run `./gradlew check`.

**DoD:** The test enumerates every README placeholder form and passes.

---

### Task 4: F-011 — Table name validation and identifier quoting

**Files:** Modify `config/src/main/kotlin/ConfigValidator.kt`,
`postgres/src/main/kotlin/InboxRepository.kt`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerOutboxRepository.kt`.
Test: `config/src/test/kotlin/org/nxtspec/ConfigValidatorTest.kt`.

**Interfaces produced:** `ConfigValidator` rejects an invalid `database.outboxTableName` or
`database.inboxTableName` with `IllegalArgumentException`.

- [ ] **Step 1:** Add two tests to `ConfigValidatorTest`: one asserts
      `outboxTableName = "outbox; DROP TABLE users --"` throws with a message naming
      `database.outboxTableName` and the environment variable; one asserts `my_schema_outbox`
      passes validation.
- [ ] **Step 2:** Run `./gradlew :config:test`. Expected: FAIL, no such validation exists.
- [ ] **Step 3:** Apply the existing SQL identifier regular expression to both table names.
- [ ] **Step 4:** Quote every interpolated identifier in raw SQL. Double quotes for PostgreSQL,
      square brackets for SQL Server.
- [ ] **Step 5:** Run `./gradlew :config:test :postgres:test :sqlserver:test`. Expected: PASS.

**DoD:** Both tests pass and a legitimate name still works end to end.

---

### Task 5: F-008 — Batched deletes

**Files:** Modify both repository interfaces, all four repository implementations, and
`outbox-service/src/main/kotlin/RetentionService.kt`.
Test: `postgres/src/test/kotlin/org/nxtspec/OutboxRepositoryTest.kt`,
`outbox-service/src/test/kotlin/org/nxtspec/RetentionServiceTest.kt`.

**Interfaces produced:**
- `suspend fun deleteOlderThan(state: String, cutoff: Instant, limit: Int): Int`
- `suspend fun deleteExceptMostRecent(state: String, keepCount: Int, limit: Int): Int`

- [ ] **Step 1:** Write a retention test that inserts 250 eligible rows with `batchSize = 100`,
      records every delete call, and asserts each call returns at most 100, that three calls
      occur for that state, and that all 250 rows are gone.
- [ ] **Step 2:** Run it. Expected: FAIL, one call deletes 250 rows.
- [ ] **Step 3:** Add the `limit` parameter to both interface methods.
      PostgreSQL: `DELETE ... WHERE id IN (SELECT id ... LIMIT n)`. SQL Server: `DELETE TOP (n)`.
- [ ] **Step 4:** Pass `tableConfig.batchSize` from `RetentionService`.
- [ ] **Step 5:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** Three delete calls of at most 100 rows each, and all 250 rows gone.

---

### Task 6: F-007 — Inbox retention uses inbox states

**Files:** Modify `outbox-service/src/main/kotlin/RetentionService.kt`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/RetentionServiceTest.kt`.

- [ ] **Step 1:** Write a test that inserts inbox rows in state `processed` older than the cutoff,
      runs one inbox cleanup cycle, and asserts the rows are deleted and the cleanup metric for
      `table="inbox"` increased by the same number.
- [ ] **Step 2:** Run it. Expected: FAIL, zero rows deleted.
- [ ] **Step 3:** Add a `states: List<String>` parameter to `deleteInBatches`. Pass
      `outboxCompletedStates` from `cleanupOutbox` and `inboxCompletedStates` from `cleanupInbox`.
- [ ] **Step 4:** Run the test. Expected: PASS. Then run `./gradlew check`.

**DoD:** The rows are deleted and `queuebox_cleanup_messages_deleted_total{table="inbox"}` rises.

---

### Task 7: F-009 — Outbox claim order and SKIP LOCKED

**Files:** Modify `postgres/src/main/kotlin/OutboxRepository.kt`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerOutboxRepository.kt`.
Test: `postgres/src/test/kotlin/org/nxtspec/OutboxRepositoryConcurrencyTest.kt`.

- [ ] **Step 1:** Write two tests. The first inserts 10 pending rows of increasing `scheduled_at`
      and asserts `claimBatch(3)` returns the three oldest, in order. The second runs two
      claimers over 100 rows and asserts disjoint sets, a union of 100, and that neither claimer
      blocks for more than one second.
- [ ] **Step 2:** Run them. Expected: FAIL on order, and the second claimer blocks.
- [ ] **Step 3:** Rewrite `claimBatch` as one statement:
      `UPDATE <outbox> SET state='processing', updated_at=now(), claimed_at=now()
       FROM (SELECT id FROM <outbox> WHERE state='pending' AND scheduled_at<=now()
       ORDER BY scheduled_at ASC, created_at ASC LIMIT ? FOR UPDATE SKIP LOCKED) c
       WHERE t.id=c.id RETURNING ...`.
      SQL Server: `UPDATE TOP (?) ... WITH (ROWLOCK, UPDLOCK, READPAST) ... OUTPUT INSERTED.*`
      driven by an ordered subquery.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** FIFO order proven and the concurrency test passes.

---

### Task 8: F-001 — Inbox claim locks base table rows

**Files:** Modify `postgres/src/main/kotlin/InboxRepository.kt`,
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt`.
Test: `postgres/src/test/kotlin/org/nxtspec/InboxRepositoryConcurrencyTest.kt`,
`sqlserver/src/test/kotlin/org/nxtspec/SqlServerInboxRepositoryConcurrencyTest.kt`.

- [ ] **Step 1:** Write both concurrency tests. Two coroutines each call `claimPending(50)` against
      100 pending rows. Assert the intersection of the returned identifier sets is empty and the
      union has size 100.
- [ ] **Step 2:** Run them. Expected: FAIL, both coroutines return the same rows.
- [ ] **Step 3:** Rewrite `claimPending` as one `UPDATE ... FROM (SELECT ... FOR UPDATE
      SKIP LOCKED) ... RETURNING` statement against the base table. Apply the one message per
      aggregate rule in Kotlin after the claim: keep the first message per aggregate, and release
      the rest to `pending` in the same transaction. Record the choice in a code comment.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** Both concurrency tests pass. `./gradlew :postgres:test :sqlserver:test` passes.

---

### Task 9: F-006 — Reclaim stale claims

**Files:** Create `postgres/src/main/resources/db/migration/V3__add_claimed_at.sql` and
`sqlserver/src/main/resources/db/migration/V2__add_claimed_at.sql`. Modify both table
definitions per database, both repository interfaces, all four repositories,
`config/src/main/kotlin/QueueBoxConfig.kt`, `outbox-service/src/main/kotlin/OutboxPoller.kt`,
`core/src/main/kotlin/metrics/*`.
Test: `postgres/src/test/kotlin/org/nxtspec/ReclaimStaleTest.kt`,
`app/src/test/kotlin/e2e/E2ECrashRecoveryTest.kt`.

**Interfaces produced:** `suspend fun reclaimStale(olderThan: Duration): Int` on both repository
interfaces. Configuration `outbox.claimTimeoutMs` default `300000`, `inbox.claimTimeoutMs`
default `300000`. Metric `queuebox_outbox_messages_reclaimed_total`.

- [ ] **Step 1:** Write the integration test. Insert a row, claim it, back-date `claimed_at`,
      run `reclaimStale`, assert the row is `pending` again and the attempt count is unchanged.
- [ ] **Step 2:** Run it. Expected: FAIL, the method does not exist.
- [ ] **Step 3:** Add the `claimed_at TIMESTAMPTZ` column in both migrations and both Exposed
      table definitions. Set it in `claimBatch` and `claimPending`.
- [ ] **Step 4:** Implement `reclaimStale` in all four repositories.
- [ ] **Step 5:** Call `reclaimStale` from the poller loop, at most once per `claimTimeoutMs / 5`.
      Record `queuebox_outbox_messages_reclaimed_total`.
- [ ] **Step 6:** Write the end to end test that cancels the poller between claim and publish and
      asserts delivery after a restart.
- [ ] **Step 7:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** Both tests pass.

---

### Task 10: F-004 — Route routing key reaches the publisher

**Files:** Modify `core/src/main/kotlin/Publisher.kt`,
`outbox-service/src/main/kotlin/OutboxPoller.kt`,
`outbox-service/src/main/kotlin/http/HttpPublisher.kt`,
`rabbitmq/src/main/kotlin/RabbitPublisher.kt`, `core/src/main/kotlin/Destination.kt`,
`README.md`.
Test: `rabbitmq/src/test/kotlin/RabbitPublisherTest.kt`,
`rabbitmq/src/test/kotlin/RabbitPublisherIntegrationTest.kt`.

**Interfaces produced:**
`data class PublishContext(val routingKey: String?)` and
`suspend fun publish(message: OutboxMessage, destination: Destination, context: PublishContext): Result<Unit>`.

- [ ] **Step 1:** Write a unit test asserting `RabbitPublisher` publishes with the routing key the
      context supplies, not the destination template.
- [ ] **Step 2:** Write an integration test that binds a queue to a topic exchange with routing key
      `eu.high.order.created`, uses the route template
      `{{ payload.region }}.{{ payload.priority }}.{{ topic }}`, and asserts delivery.
- [ ] **Step 3:** Run them. Expected: FAIL, the destination template wins.
- [ ] **Step 4:** Add `PublishContext` to `Publisher.publish`. Pass `routingResult.routingKey`
      from `OutboxPoller`. Make `RabbitPublisher` prefer the context key and fall back to the
      destination template when the route sets none.
- [ ] **Step 5:** State the precedence in the README.
- [ ] **Step 6:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** Both tests pass.

---

### Task 11: F-003 — Register the RabbitMQ publisher

**Files:** Modify `app/src/main/kotlin/App.kt`.
Test: `app/src/test/kotlin/PublisherRegistrationTest.kt`,
`app/src/test/kotlin/e2e/E2EOutboxFlowTest.kt`.

**Interfaces produced:**
`fun validatePublisherCoverage(destinations: Collection<Destination>, publishers: List<Publisher>)`
throws `UnsupportedDestinationException` naming the destination and its type.

- [ ] **Step 1:** Write a startup test asserting a configuration whose destination type has no
      registered publisher fails with `UnsupportedDestinationException` naming the destination.
- [ ] **Step 2:** Add an `E2EOutboxFlowTest` case that inserts an outbox row routed to a RabbitMQ
      destination and asserts the message arrives on the exchange and the row reaches `sent`.
- [ ] **Step 3:** Run them. Expected: FAIL, the row is dead-lettered.
- [ ] **Step 4:** Construct `RabbitPublisher` in `App.kt`, add it to `publishers`, close it in the
      shutdown hook, and call `validatePublisherCoverage` before the server starts.
- [ ] **Step 5:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** Both tests pass.

---

### Task 12: F-002 — Inbox relay

**Files:** Create `inbox-service/src/main/kotlin/InboxRelay.kt`. Modify
`config/src/main/kotlin/QueueBoxConfig.kt`, `app/src/main/kotlin/App.kt`,
`core/src/main/kotlin/metrics/*`, `README.md`.
Test: `inbox-service/src/test/kotlin/org/nxtspec/InboxRelayTest.kt`,
`app/src/test/kotlin/e2e/E2EInboxRelayTest.kt`.

**Interfaces consumed:** `reclaimStale` from Task 9, `claimPending` from Task 8.

**Interfaces produced:**
- `class InboxRelay(config: InboxRelayConfig, inboxRepository, outboxRepository, sourceTopics: Map<String, String>, metricsCollector)`
  with `fun start()` and `suspend fun shutdown()`.
- Configuration `inbox.relay.enabled` default true, `inbox.relay.pollIntervalMs` default 100,
  `inbox.relay.batchSize` default 100, `inbox.relay.claimTimeoutMs` default 300000.
- Per-source `sources.<name>.topic`, a template over `{{ source }}` and `{{ eventType }}`,
  default `{{ eventType }}`.
- `suspend fun insert(message: OutboxMessage)` on `OutboxRepositoryInterface`.
- Metrics `queuebox_inbox_messages_total{status="forwarded"}` and
  `queuebox_inbox_relay_errors_total`.

Field mapping, fixed: `outbox.topic` from the rendered source template; `outbox.key` from the
inbox `aggregate_id`; `outbox.payload` from the stored inbox payload; `outbox.headers` carry
`x-inbox-id`, `x-source`, and `x-idempotency-key`. A template that renders empty fails the
message to dead.

- [ ] **Step 1:** Write `InboxRelayTest` for the field mapping and for the empty template case.
- [ ] **Step 2:** Write `E2EInboxRelayTest`. Post to an inbox HTTP source. Assert, in order: the
      inbox row reaches `processed`; a matching outbox row exists with the mapped topic, key, and
      headers; the message is delivered to the configured destination.
- [ ] **Step 3:** Write a test asserting that a failed outbox insert leaves the inbox row
      recoverable and that the F-006 reclaim returns it to `pending`.
- [ ] **Step 4:** Write a test asserting two relay replicas forwarding 100 messages produce exactly
      100 outbox rows.
- [ ] **Step 5:** Run them. Expected: FAIL, no relay exists.
- [ ] **Step 6:** Implement `InboxRelay` on the `OutboxPoller` model. The outbox insert and the
      `markProcessed` call run in one transaction.
- [ ] **Step 7:** Wire the relay into `App.kt` and the shutdown hook.
- [ ] **Step 8:** Rewrite the README "Aggregate Ordering" section to state exactly what the code
      does, and document the path: receive, deduplicate, transform, store, forward, route, deliver.
      State that QueueBox never interprets the payload.
- [ ] **Step 9:** Run the tests. Expected: PASS. Then run `./gradlew check`.

**DoD:** All four tests pass and the README matches the code.

---

## Phase exit condition

`./gradlew clean build check jacocoAggregatedReport` passes, every Phase 1 finding is closed with
evidence, and every README claim maps to a passing test.
