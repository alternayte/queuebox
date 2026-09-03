# Phase 2 — Durability and correctness: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close finding F-013 to F-033. Remove the operational risks that a serious adopter
meets in the first week.

**Architecture:** The message path keeps its shape. The poller gains per-message isolation,
bounded concurrency, an error column, and a bounded shutdown. The RabbitMQ client gains a cached
channel, serialised acknowledgement, and a return listener. The inbox HTTP endpoint gains a body
limit and a rate limit. Flyway runs the migrations at startup.

**Tech Stack:** Kotlin, Gradle, Exposed, Ktor, coroutines, Flyway, PostgreSQL, SQL Server,
RabbitMQ, Testcontainers, JUnit 5, JaCoCo.

**Spec:** `hardening-doc.md`, section 5.

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed.
- Write the test first. Confirm the failure. Then write the fix.
- Every finding closes with command output as evidence.
- `./gradlew check` must pass after every finding.
- At the end of the phase, raise the coverage gates to 80 percent aggregate line, 70 percent
  aggregate branch, and 60 percent per module. Decision 3 of section 2A requires it.

## File structure

| File | Responsibility |
|------|----------------|
| `outbox-service/src/main/kotlin/OutboxPoller.kt` | Per-message isolation, concurrency, gauge interval, error persistence, bounded shutdown. |
| `outbox-service/src/main/kotlin/RetentionService.kt` | Cancellable cleanup loop. |
| `outbox-service/src/main/kotlin/MessageRouter.kt` | Precompiled, anchored, escaped topic patterns. |
| `core/src/main/kotlin/IdempotencyExtractor.kt` | One parse per message. |
| `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt` | `scheduleRetry` and `markDead` carry the error. |
| `postgres`, `sqlserver` repositories and migrations | `last_error` column, identifier quoting. |
| `rabbitmq/src/main/kotlin/RabbitPublisher.kt` | Cached confirm channel, return listener. |
| `rabbitmq/src/main/kotlin/RabbitConsumer.kt` | Serialised acknowledgement, draining stop. |
| `inbox-service/src/main/kotlin/InboxRoutes.kt` | Body limit, rate limit. |
| `app/src/main/kotlin/App.kt` | Server shutdown order, Flyway. |
| `docs/development/migrations.md` | Migration policy. |

---

### Task 1: F-026 — Precompiled, escaped topic patterns

**Files:** Modify `outbox-service/src/main/kotlin/MessageRouter.kt`,
`config/src/main/kotlin/ConfigValidator.kt`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/MessageRouterTest.kt`.

- [ ] **Step 1:** Write tests that a topic containing `+`, `(`, `[`, and the literal `§§§` matches
      its pattern correctly, and a test that asserts the patterns compile once for N routed
      messages.
- [ ] **Step 2:** Run them. Expected: FAIL on the metacharacter cases.
- [ ] **Step 3:** Precompile every pattern in the constructor. Split the pattern on `**` and `*`
      and apply `Regex.escape` to each literal segment. Anchor the expression.
- [ ] **Step 4:** Validate the pattern in `ConfigValidator`.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 2: F-025 — One JSON parse per message

**Files:** Modify `core/src/main/kotlin/IdempotencyExtractor.kt` and its callers.
Test: `core/src/test/kotlin/org/nxtspec/IdempotencyExtractorTest.kt`.

**Interfaces produced:** `fun extractAll(payload: JsonElement, paths: Map<String, String>): Map<String, String?>`
plus the existing `extract`, which keeps working.

- [ ] **Step 1:** Write a call-counting test that asserts one parse per message regardless of the
      number of configured paths.
- [ ] **Step 2:** Run it. Expected: FAIL, one parse per path.
- [ ] **Step 3:** Parse once into a `DocumentContext` and read every path from it.
- [ ] **Step 4:** Run the test, then `./gradlew check`.

---

### Task 3: F-027 and F-028 — Bounded, cancellable shutdown

**Files:** Modify `outbox-service/src/main/kotlin/RetentionService.kt`,
`outbox-service/src/main/kotlin/OutboxPoller.kt`, `config/src/main/kotlin/QueueBoxConfig.kt`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/RetentionServiceTest.kt`,
`outbox-service/src/test/kotlin/org/nxtspec/OutboxPollerTest.kt`.

**Interfaces produced:** `outbox.shutdownTimeoutMs` default 30000.

- [ ] **Step 1:** Write a test that `RetentionService.stop()` returns in under two seconds with a
      one hour cleanup interval, and a test that `OutboxPoller.shutdown()` returns within the
      timeout when a publisher never returns.
- [ ] **Step 2:** Run them. Expected: FAIL, both hang.
- [ ] **Step 3:** Replace join-before-cancel with `withTimeout` around the joins, then `cancel()`.
      Log the abandoned message count.
- [ ] **Step 4:** Run the tests, then `./gradlew check`.

---

### Task 4: F-013, F-014, F-015 — Poller isolation, concurrency and gauge interval

**Files:** Modify `outbox-service/src/main/kotlin/OutboxPoller.kt`,
`config/src/main/kotlin/QueueBoxConfig.kt`, `core/src/main/kotlin/metrics/*`, `README.md`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/OutboxPollerTest.kt`.

**Interfaces produced:** `outbox.concurrency` default 8, `outbox.pendingGaugeIntervalMs`
default 5000, metric `queuebox_outbox_process_errors_total`.

- [ ] **Step 1:** Write three tests. A repository that throws on the third of five messages leaves
      the other four delivered and the third scheduled for retry. Ten messages against a
      destination with 200 ms latency complete in under one second with `concurrency = 8`. A
      counting repository is asked for the pending count at most once per five seconds.
- [ ] **Step 2:** Run them. Expected: FAIL on all three.
- [ ] **Step 3:** Wrap each `processMessage` in try/catch and apply the retry strategy on failure.
      Process the batch inside `coroutineScope` with a bounded `Semaphore`. Gate the gauge update
      behind the interval.
- [ ] **Step 4:** Document the ordering consequence of concurrency in the README.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 5: F-016 and F-017 — The error column and one attempt per failure

**Files:** Create `postgres/src/main/resources/db/migration/V4__add_last_error.sql` and
`sqlserver/src/main/resources/db/migration/V3__add_last_error.sql`. Modify both outbox table
definitions per database, `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt`, both
outbox repositories, `outbox-service/src/main/kotlin/OutboxPoller.kt`.
Test: `postgres/src/test/kotlin/org/nxtspec/OutboxRepositoryTest.kt`,
`app/src/test/kotlin/e2e/E2EOutboxFlowTest.kt`.

**Interfaces produced:**
- `suspend fun scheduleRetry(id: UUID, delayMs: Long, error: String?)`
- `suspend fun markDead(id: UUID, error: String?)`
- `markFailed` is removed, because F-016 folds the error into `scheduleRetry`.

- [ ] **Step 1:** Write a test that after a 500 response the row `last_error` contains the status
      code and does not contain the destination `Authorization` header value. Write a test that
      `attempt` increases by exactly one per failed delivery across five retries.
- [ ] **Step 2:** Run them. Expected: FAIL, no column and no persisted error.
- [ ] **Step 3:** Add the `last_error` column in both migrations and both table definitions.
      Truncate the error to 2000 characters and redact the known secret-bearing headers.
- [ ] **Step 4:** Make `scheduleRetry` the only method that increments `attempt`.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 6: F-020, F-021 and F-022 — The RabbitMQ publisher

**Files:** Modify `rabbitmq/src/main/kotlin/RabbitPublisher.kt`, `README.md`.
Test: `rabbitmq/src/test/kotlin/RabbitPublisherIntegrationTest.kt`,
`rabbitmq/src/test/kotlin/RabbitPublisherThroughputTest.kt`.

- [ ] **Step 1:** Write a test that publishing 1000 messages creates fewer than 10 channels, and a
      test that publishing to a topic exchange with no matching binding fails the publish.
- [ ] **Step 2:** Run them. Expected: FAIL, one channel per message and a silent drop.
- [ ] **Step 3:** Cache one confirm-enabled channel per destination behind a mutex. Declare the
      exchange once. Recreate the channel on error.
- [ ] **Step 4:** Register a `ReturnListener`, correlate a return to the pending confirm, and fail
      the publish so the retry path runs.
- [ ] **Step 5:** State the measured throughput in the README and name the test that produced it.
- [ ] **Step 6:** Run the tests, then `./gradlew check`.

---

### Task 7: F-018 and F-019 — The RabbitMQ consumer

**Files:** Modify `rabbitmq/src/main/kotlin/RabbitConsumer.kt`.
Test: `rabbitmq/src/test/kotlin/RabbitConsumerIntegrationTest.kt`.

- [ ] **Step 1:** Write a test that publishes 500 messages with `prefetchCount = 50` and asserts
      every message is acknowledged, the channel is still open, and no message is redelivered.
      Write a test that after `stop()` every message that reached the database was acknowledged.
- [ ] **Step 2:** Run them. Expected: FAIL or flake, because the channel is not thread safe.
- [ ] **Step 3:** Send acknowledgement commands to one actor coroutine that owns the channel.
- [ ] **Step 4:** In `stop`, cancel the consumer tag with `basicCancel`, wait for the in-flight
      jobs with a bounded timeout, then close the channel.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 8: F-023 and F-024 — Body limit and rate limit

**Files:** Modify `inbox-service/src/main/kotlin/InboxRoutes.kt`,
`config/src/main/kotlin/QueueBoxConfig.kt`, `app/src/main/kotlin/App.kt`.
Test: `inbox-service/src/test/kotlin/org/nxtspec/InboxRoutesTest.kt`.

**Interfaces produced:** `inbox.maxBodyBytes` default 1048576,
`sources.<name>.rateLimit.requestsPerMinute` optional.

- [ ] **Step 1:** Write a test that a body one byte over the limit returns 413, and a test that the
      61st request in a minute against a source limited to 60 returns 429 with `Retry-After`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Reject an oversized body before reading it, using the `Content-Length` header and
      a counting read. Cap the `DoubleReceive` buffer and the Netty request size.
- [ ] **Step 4:** Install Ktor's `RateLimit` plugin per source.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 9: F-029 — Stop the Ktor server during shutdown

**Files:** Modify `app/src/main/kotlin/App.kt`.
Test: `app/src/test/kotlin/e2e/E2EShutdownTest.kt`.

- [ ] **Step 1:** Write a test that issues a slow inbox request, triggers the shutdown sequence,
      and asserts the request completes with a success status rather than 500.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Hold the `EmbeddedServer` reference. Stop the server first with a grace period,
      then the background services, then the resources. Extract the sequence into a testable
      `ShutdownSequence` class.
- [ ] **Step 4:** Run the test, then `./gradlew check`.

---

### Task 10: F-030 and F-031 — Run the migrations

**Files:** Modify `gradle/libs.versions.toml`, `app/build.gradle.kts`,
`app/src/main/kotlin/App.kt`, `config/src/main/kotlin/QueueBoxConfig.kt`. Rename the SQL Server
migrations. Create `docs/development/migrations.md`.
Test: `app/src/test/kotlin/e2e/E2EMigrationTest.kt`.

**Interfaces produced:** `database.migrate` default true.

- [ ] **Step 1:** Write an end to end test that starts against an empty PostgreSQL container with
      no init script and succeeds. Write the same test for SQL Server.
- [ ] **Step 2:** Run them. Expected: FAIL, no table exists.
- [ ] **Step 3:** Add Flyway to the version catalog. Run `Flyway.migrate()` at startup behind
      `database.migrate`.
- [ ] **Step 4:** Split the SQL Server `V1__create_tables.sql` so the two providers correspond one
      to one. Write `docs/development/migrations.md` with the policy: one logical change per file,
      never edit a released file.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 11: F-032 and F-033 — Identifier quoting and retention semantics

**Files:** Modify `README.md`.
Test: `postgres/src/test/kotlin/org/nxtspec/CustomTableNameTest.kt`,
`sqlserver/src/test/kotlin/org/nxtspec/SqlServerCustomColumnTest.kt`,
`outbox-service/src/test/kotlin/org/nxtspec/RetentionBatchingTest.kt`.

- [ ] **Step 1:** Write a test that a column mapping using `order` and `user` passes end to end on
      both providers.
- [ ] **Step 2:** Run them. Expected: PASS or FAIL. Phase 1 added the quoting helpers, so record
      the result either way.
- [ ] **Step 3:** Fix any remaining unquoted identifier.
- [ ] **Step 4:** State in the README retention section that outbox age uses `updated_at` and inbox
      age uses `created_at`, which is the receipt time. Assert both in a test.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 12: Raise the coverage gates

**Files:** Modify `build.gradle.kts`, `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.

Decision 3 of section 2A: 80 percent aggregate line, 70 percent aggregate branch, 60 percent per
module. Raise them at the end of Phase 2.

- [ ] **Step 1:** Raise the three thresholds.
- [ ] **Step 2:** Run `./gradlew check`. Add tests for the least covered production code until the
      gates pass.

---

## Phase exit condition

All major findings closed. The new integration tests for crash recovery, concurrency and
retention pass. `./gradlew clean build check jacocoAggregatedReport` passes with the raised gates.
