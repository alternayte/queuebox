# Phase 4 — Observability and operations: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close finding F-046 to F-057. Make QueueBox operable by a person who did not write it.

**Architecture:** SLF4J with Logback replaces every `println`. A correlation identifier travels
from the inbound request to the outbound publish. The health endpoint splits into liveness and
readiness, and readiness asks named contributors. An optional management port carries the
operational endpoints. Two operations documents carry runnable SQL, and a test executes it.

**Tech Stack:** Kotlin, Gradle, SLF4J, Logback, Ktor, Micrometer, PostgreSQL, Testcontainers,
JUnit 5, JaCoCo.

**Spec:** `hardening-doc.md`, section 7.

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed.
- Write the test first. Confirm the failure. Then write the fix.
- Every finding closes with command output as evidence.
- `./gradlew check` must pass after every finding, with the Phase 2 coverage gates in force.

## File structure

| File | Responsibility |
|------|----------------|
| `core/src/main/kotlin/logging/Logging.kt` | The logger factory and the MDC keys. |
| `app/src/main/resources/logback.xml` | The text profile and the JSON profile. |
| `core/src/main/kotlin/BuildInfo.kt` | The generated version that the info metric reports. |
| `app/src/main/kotlin/HealthRoutes.kt`, `HealthManager.kt` | Liveness, readiness, and the contributors. |
| `app/src/main/kotlin/App.kt` | The management port, the startup retry, and the startup validation. |
| `core/src/main/kotlin/metrics/QueueBoxMetrics.kt` | The metrics that F-052 names. |
| `docs/operations/runbook.md`, `docs/operations/dead-letter.md` | The operations documents. |
| `postgres/src/main/resources/db/postgresql/V5__add_correlation_id.sql` | The correlation column. |

---

### Task 1: F-046 — A logging framework

**Files:** Create `core/src/main/kotlin/logging/Logging.kt`, `app/src/main/resources/logback.xml`.
Modify `gradle/libs.versions.toml`, every module build file, and the five files that call
`println`.
Test: `app/src/test/kotlin/LoggingTest.kt`.

- [ ] **Step 1:** Write a test that a failed publish emits one WARN line that contains the
      message identifier. Use a Logback list appender.
- [ ] **Step 2:** Run `grep -rn "println(" --include='*.kt' */src/main`. Record the 15 results.
- [ ] **Step 3:** Add `slf4j-api` to `core` and `logback-classic` to the `app` runtime. Add the
      logger factory and the MDC keys.
- [ ] **Step 4:** Replace every `println` with a named logger call at the correct level.
- [ ] **Step 5:** Add `logback.xml` with a text encoder and a JSON encoder, selected by
      `LOG_FORMAT`.
- [ ] **Step 6:** Run the grep again. Expected: zero results. Run the test, then `./gradlew check`.

---

### Task 2: F-053 and F-052 — The version and the metric gaps

**Files:** Modify `build.gradle.kts`, `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`,
`core/src/main/kotlin/metrics/QueueBoxMetrics.kt`,
`core/src/main/kotlin/metrics/MetricsCollectorInterface.kt`,
`app/src/main/kotlin/MetricsCollector.kt`, the callers that record, and `README.md`.
Test: `core/src/test/kotlin/org/nxtspec/metrics/QueueBoxMetricsTest.kt`,
`app/src/test/kotlin/MetricsRoutesTest.kt`.

**Interfaces produced:** `BuildInfo.version` from a generated resource. New counters for
per-destination success and failure, transform failures by strategy, inbox rejections by reason,
HTTP status class, and a queue depth gauge by destination.

- [ ] **Step 1:** Write a test that the info metric tag equals the Gradle project version. Write a
      test that scrapes `/metrics` and asserts every documented name is present.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Generate the build information resource and read it.
- [ ] **Step 4:** Add the counters with bounded label sets. Never use a message identifier or a
      raw error string as a label.
- [ ] **Step 5:** List every emitted metric in the README table.
- [ ] **Step 6:** Run the tests, then `./gradlew check`.

---

### Task 3: F-049, F-050 and F-051 — Health and the management port

**Files:** Modify `app/src/main/kotlin/HealthRoutes.kt`, `app/src/main/kotlin/HealthManager.kt`,
`app/src/main/kotlin/App.kt`, `README.md`.
Test: `app/src/test/kotlin/HealthRoutesTest.kt`, `app/src/test/kotlin/HealthManagerTest.kt`,
`app/src/test/kotlin/ManagementPortTest.kt`.

**Interfaces produced:** `server.managementPort: Int?`, `/health/live`, `/health/ready`, a
`HealthContributor` interface with a name and a check.

- [ ] **Step 1:** Write tests: `/health/live` returns 200 with a broken data source,
      `/health/ready` returns 503, a stopped poller turns readiness unhealthy with a named
      component, and `/metrics` returns 404 on the data port when a management port is set.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Split the endpoint. Add the contributors for the poller, the retention service,
      the relay, and each RabbitMQ connection.
- [ ] **Step 4:** Serve the operational endpoints on the management port when it is set.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 4: F-054 and F-055 — The operations documents

**Files:** Create `docs/operations/runbook.md`, `docs/operations/dead-letter.md`.
Test: `postgres/src/test/kotlin/org/nxtspec/RunbookSqlTest.kt`,
`app/src/test/kotlin/e2e/E2EDeadLetterReplayTest.kt`.

- [ ] **Step 1:** Write the runbook covering the five scenarios: inspect a dead-lettered message,
      replay one, react to a growing pending gauge, size the pool and the batch, and diagnose a
      slow destination. Every scenario carries concrete SQL or a command.
- [ ] **Step 2:** Write `docs/operations/dead-letter.md` with the SQL to list dead messages and to
      requeue one.
- [ ] **Step 3:** Write a test that extracts every SQL block from both documents and executes it
      against the shipped schema.
- [ ] **Step 4:** Write an end to end test that dead-letters a message, runs the documented
      requeue SQL, and asserts the message is then delivered.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 5: F-047 — Correlation identifier

**Files:** Create `postgres/src/main/resources/db/postgresql/V5__add_correlation_id.sql` and the
SQL Server counterpart. Modify both inbox table definitions, `InboxMessage`, `InboxHandler`,
`InboxRoutes`, `InboxRelay`, `OutboxPoller`, `HttpPublisher`, `RabbitPublisher`.
Test: `app/src/test/kotlin/e2e/E2ECorrelationTest.kt`.

- [ ] **Step 1:** Write an end to end test that posts with `X-Correlation-Id`, then asserts the
      same value is in the inbox row, in the outbox row headers, and on the outbound request.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Accept the header, generate one when it is absent, store it, forward it, and put
      it in the MDC.
- [ ] **Step 4:** Run the test, then `./gradlew check`.

---

### Task 6: F-056 and F-057 — Startup behaviour

**Files:** Modify `app/src/main/kotlin/App.kt`, `postgres/src/main/kotlin/DatabaseFactory.kt`,
`config/src/main/kotlin/ConfigValidator.kt`, `config/src/main/kotlin/QueueBoxConfig.kt`.
Test: `app/src/test/kotlin/e2e/E2EStartupTest.kt`,
`config/src/test/kotlin/org/nxtspec/ConfigValidatorTest.kt`.

**Interfaces produced:** `database.startupTimeoutMs` default 60000.

- [ ] **Step 1:** Write a test that the connection retry succeeds against a database that becomes
      available after five seconds. Write a test that a syntactically invalid destination
      transform fails startup with a message naming
      `destinations.<name>.transform.expression`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Retry the initial connection with backoff, and log one clear line per attempt.
- [ ] **Step 4:** Compile every configured transform expression at startup.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 7: F-048 — The tracing promise

**Files:** Modify `README.md`.

The document allows either implementation or a qualified roadmap entry. QueueBox states the
target version, because tracing is a feature and Phase 4 closes operational defects.

- [ ] **Step 1:** Replace the unqualified roadmap line with a line that names the target version
      and states that 1.0 does not ship tracing.

---

## Phase exit condition

Structured logging replaces every `println`. Graceful shutdown proven by test. Runbook published.
`./gradlew clean build check jacocoAggregatedReport` passes.
