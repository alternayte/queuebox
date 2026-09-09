# Phase 9 — Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an operator the three things they need and do not have: a lag metric in seconds, a
way to resend a range of rows, and an error that names its own fix.

**Architecture:** Both relays already poll, and the outbox poller already updates a count gauge on
every tick. The age gauges follow that exact pattern: the poll computes the age of the oldest
pending row and caches it, and the gauge reads the cache, so a scrape costs no query. The replay
endpoint joins the existing admin route tree behind the existing guard, and moves selected rows
back to `pending` with a reset attempt. The RabbitMQ consumer catches the queue-not-found failure
and raises an error that names the cause, the asymmetry and the setting that changes it.

**Tech Stack:** Kotlin, Gradle, Ktor, Micrometer, Exposed, Testcontainers, JUnit 5. PostgreSQL 16
and SQL Server 2022. RabbitMQ.

**Spec:** `docs/superpowers/specs/2026-09-08-adoption-work-order-design.md`, findings F-094 to
F-097.

## Global Constraints

- The exit condition of EVERY task is the project's own gate commands, not a suite the implementer
  chooses: `./gradlew check` AND `./gradlew ktlintCheck detekt`. Phase 7 shipped two red builds
  because tasks ran only their own module's tests.
- RUN THE NARROWED TEST FIRST, in the foreground, before the full gate. A full `check` takes eight
  to twelve minutes on this machine and a narrowed module test takes two. Six agents across the
  previous phases stalled by backgrounding a long run and waiting for a notification. Never put a
  build or test in the background; if one auto-backgrounds on a default timeout, poll its output
  file to completion.
- ANY NEW CONFIGURATION FIELD NEEDS A REACHABILITY TEST, from YAML text through to the object that
  consumes it. Phase 8 found an entire feature that was unreachable from YAML because every test
  constructed domain objects directly. `app/src/test/kotlin/DestinationFromYamlTest.kt` is the
  shape to copy. This applies to F-097's `declareQueue`.
- For every test, name the edit to the production code that would make it fail. If you cannot name
  one, the test proves nothing. Reviewers in the previous phases found this repeatedly, including
  a security guard that could not fail and tests that passed with the production rule deleted.
- Write the test first. Run it. Confirm it fails FOR THE RIGHT REASON. Then implement.
- A migration is additive and NEVER edits a shipped file. V9 is the highest on `main`. This phase
  needs no migration; if you believe it does, stop and report.
- Prose follows ASD-STE100: no contraction, no `-ing` form outside a technical name, active voice,
  and `must` or `can` rather than `should` or `may`. Prose only, never code or identifiers.
- Reference the finding ID in every commit message, for example `F-094`.
- Never run `git revert`, `git reset --hard`, `git checkout -- .`, `git restore`, or `git clean`.

---

## File Structure

**F-094 and F-095, the age gauges:**
- Modify: `core/src/main/kotlin/metrics/QueueBoxMetrics.kt` — two gauges and their backing values
- Modify: `core/src/main/kotlin/metrics/MetricsCollectorInterface.kt` — two update methods
- Modify: `app/src/main/kotlin/MetricsCollector.kt` — the implementations
- Modify: `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt` and the inbox twin
- Modify: `postgres/src/main/kotlin/OutboxRepository.kt`, `postgres/src/main/kotlin/InboxRepository.kt`
  and both SQL Server repositories
- Modify: `outbox-service/src/main/kotlin/OutboxPoller.kt:109` and
  `inbox-service/src/main/kotlin/InboxRelay.kt` — call the new query on the poll cycle

**F-096, replay:**
- Create: `app/src/main/kotlin/dto/ReplayDto.kt` — the request and response shapes
- Modify: `app/src/main/kotlin/AdminRoutes.kt` — the route
- Modify: the outbox repository interface and all four implementations — the replay statement

**F-097, the queue error:**
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt` — catch and re-raise
- Modify: `config/src/main/kotlin/QueueBoxConfig.kt` — the `declareQueue` source setting
- Modify: `app/src/main/kotlin/App.kt` — carry it to the consumer config

---

## Task 1: The outbox reports the age of its oldest pending row (F-094)

**Files:**
- Modify: `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt:29`
- Modify: `postgres/src/main/kotlin/OutboxRepository.kt:197` and the SQL Server twin
- Modify: `core/src/main/kotlin/metrics/QueueBoxMetrics.kt:41`
- Modify: `core/src/main/kotlin/metrics/MetricsCollectorInterface.kt:66`
- Modify: `app/src/main/kotlin/MetricsCollector.kt:94`
- Modify: `outbox-service/src/main/kotlin/OutboxPoller.kt:109`

**Interfaces:**
- Produces: `OutboxRepositoryInterface.oldestPendingAgeSeconds(): Double`, returning 0.0 when no row
  is pending, and `MetricsCollectorInterface.updateOutboxOldestPendingAge(seconds: Double)`. Task 2 adds
  the inbox twin with the same names and the same zero-when-empty contract.

- [ ] **Step 1: Write the failing repository test**

Add to the existing outbox repository test class for each dialect, beside the `countByState` tests.

```kotlin
@Test
fun `the oldest pending age is zero on an empty table`() = runBlocking {
    assertEquals(0.0, repository.oldestPendingAgeSeconds())
}

@Test
fun `the oldest pending age measures the oldest pending row`() = runBlocking {
    insertOutboxRowCreatedSecondsAgo(seconds = 60)
    insertOutboxRowCreatedSecondsAgo(seconds = 10)

    val age = repository.oldestPendingAgeSeconds()

    // The oldest row is 60 seconds old. The tolerance absorbs the round trip, and it must stay
    // wide enough that a loaded machine does not fail the test. The previous phase fixed several
    // tests that asserted a wall clock too tightly.
    assertTrue(age in 55.0..75.0, "the age was $age")
}

@Test
fun `a row that is not pending does not count`() = runBlocking {
    val id = insertOutboxRowCreatedSecondsAgo(seconds = 300)
    repository.markSent(id)

    assertEquals(0.0, repository.oldestPendingAgeSeconds())
}
```

Write `insertOutboxRowCreatedSecondsAgo` in the test class. It must set `created_at` explicitly to
a past instant, not sleep. Confirm the real name of the mark-sent method on the repository before
you use it.

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :postgres:test --tests '*Outbox*'` with a timeout you set.
Expected: FAIL to compile, because `oldestPendingAgeSeconds` does not exist.

- [ ] **Step 3: Add the query**

Add to `OutboxRepositoryInterface`:

```kotlin
    /**
     * The age in seconds of the oldest row in state 'pending', or 0.0 when no row is pending.
     *
     * F-094. A count of pending rows cannot tell an operator whether the relay is busy or dead.
     * The age can. The poll cycle calls this and caches the result, so a metrics scrape costs no
     * query.
     */
    suspend fun oldestPendingAgeSeconds(): Double
```

Implement it in all four repositories. Compute the age in the DATABASE, not in Kotlin, so a clock
difference between the application host and the database host cannot distort it. PostgreSQL:
`SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0) FROM outbox WHERE state = 'pending'`.
SQL Server: the `DATEDIFF` equivalent against `SYSUTCDATETIME()`. Respect the column mapping, as
the neighbouring queries do.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `./gradlew :postgres:test --tests '*Outbox*' :sqlserver:test --tests '*Outbox*'`
Expected: PASS, both dialects.

- [ ] **Step 5: Add the gauge**

In `QueueBoxMetrics.kt`, beside `outboxMessagesPending`:

```kotlin
    private val oldestPendingAgeSeconds = AtomicReference(0.0)

    // F-094: the age of the oldest pending row. A count cannot separate a busy relay from a dead
    // one. The poll cycle refreshes this value, so a scrape reads a number and runs no query.
    val outboxOldestPendingAge: Gauge = Gauge
        .builder("queuebox_outbox_oldest_pending_age_seconds", oldestPendingAgeSeconds) { it.get() }
        .description("Age in seconds of the oldest pending outbox message")
        .register(registry)

    fun updateOutboxOldestPendingAge(seconds: Double) = oldestPendingAgeSeconds.set(seconds)
```

Add `updateOutboxOldestPendingAge(seconds: Double)` to `MetricsCollectorInterface` and implement it in
`MetricsCollector`, following `updatePendingCount` exactly.

- [ ] **Step 6: Call it from the poll cycle**

`OutboxPoller.kt:109` already reads the pending count on every tick. Add the age beside it:

```kotlin
        collector.updatePendingCount(repository.countByState("pending"))
        collector.updateOutboxOldestPendingAge(repository.oldestPendingAgeSeconds())
```

- [ ] **Step 7: Write the end to end test**

The DoD requires a scrape, not only a repository call.

```kotlin
@Test
fun `the metrics endpoint reports the age of the oldest pending outbox row`() = runBlocking {
    insertOutboxMessage(topic = "orders.created", payload = """{}""")

    // The poller refreshes the gauge on its tick, so wait for the value rather than assume it.
    assertTrue(awaitUntil { scrapeMetric("queuebox_outbox_oldest_pending_age_seconds") > 0.0 })
}
```

Write `scrapeMetric(name: String): Double` in `E2ETestBase`, beside the existing helpers, so
Task 2 can reuse it. Read that file before you write it and follow its style.

- [ ] **Step 8: Run the gates and commit**

Run the narrowed tests first, then `./gradlew check` and `./gradlew ktlintCheck detekt`.

```bash
git add core postgres sqlserver outbox-service app
git commit -m "feat(F-094): report the age of the oldest pending outbox row in seconds"
```

---

## Task 2: The inbox reports the age of its oldest pending row (F-095)

**Files:**
- Modify: `core/src/main/kotlin/repository/InboxRepositoryInterface.kt:27`
- Modify: `postgres/src/main/kotlin/InboxRepository.kt:218` and the SQL Server twin
- Modify: `core/src/main/kotlin/metrics/QueueBoxMetrics.kt`
- Modify: `core/src/main/kotlin/metrics/MetricsCollectorInterface.kt`
- Modify: `app/src/main/kotlin/MetricsCollector.kt`
- Modify: `inbox-service/src/main/kotlin/InboxRelay.kt:64-76`

**Interfaces:**
- Consumes: the shape Task 1 established, and `scrapeMetric` from `E2ETestBase`.
- Produces: `InboxRepositoryInterface.oldestPendingAgeSeconds(): Double` and
  `MetricsCollectorInterface.updateInboxOldestPendingAge(seconds: Double)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `the oldest pending inbox age is zero on an empty table`() = runBlocking {
    assertEquals(0.0, repository.oldestPendingAgeSeconds())
}

@Test
fun `the oldest pending inbox age measures the oldest pending row`() = runBlocking {
    insertInboxRowCreatedSecondsAgo(seconds = 60)
    insertInboxRowCreatedSecondsAgo(seconds = 10)

    val age = repository.oldestPendingAgeSeconds()

    // The tolerance stays wide on purpose. A loaded machine must not fail this test; the previous
    // phase repaired several tests that asserted a wall clock too tightly.
    assertTrue(age in 55.0..75.0, "the age was $age")
}

@Test
fun `an inbox row that is not pending does not count`() = runBlocking {
    val id = insertInboxRowCreatedSecondsAgo(seconds = 300)
    repository.markProcessed(id)

    assertEquals(0.0, repository.oldestPendingAgeSeconds())
}
```

Write `insertInboxRowCreatedSecondsAgo` in the test class, setting `created_at` explicitly rather
than sleeping. Confirm the real name of the mark-processed method before you use it.

Then the end to end test:

```kotlin
@Test
fun `the metrics endpoint reports the age of the oldest pending inbox row`() = runBlocking {
    insertInboxMessage(source = "orders", idempotencyKey = "k1")
    startRelay()

    assertTrue(awaitUntil { scrapeMetric("queuebox_inbox_oldest_pending_age_seconds") > 0.0 })
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :postgres:test --tests '*Inbox*'` with a timeout you set.

- [ ] **Step 3: Implement, following Task 1 exactly**

Same query shape against the inbox table, same gauge shape, same zero-when-empty contract. The
metric name is `queuebox_inbox_oldest_pending_age_seconds`.

- [ ] **Step 4: Call it from the relay's poll loop**

`InboxRelay.kt` loops at line 64 and delays at line 76. The relay does not currently touch the
metrics collector on its tick, so add the call inside the loop, beside the existing work, and
guard it the way the relay guards its other optional collaborators. Read the loop first: if the
collector is nullable there, use the same safe-call style the file already uses.

- [ ] **Step 5: Run the gates and commit**

```bash
git add core postgres sqlserver inbox-service app
git commit -m "feat(F-095): report the age of the oldest pending inbox row in seconds"
```

---

## Task 3: Replay moves selected rows back to pending (F-096)

**Files:**
- Create: `app/src/main/kotlin/dto/ReplayDto.kt`
- Modify: `app/src/main/kotlin/AdminRoutes.kt:39`
- Modify: `core/src/main/kotlin/repository/OutboxRepositoryInterface.kt`
- Modify: all four outbox repository implementations
- Test: `app/src/test/kotlin/` and the repository test classes

**Interfaces:**
- Produces: `OutboxRepositoryInterface.replay(filter: ReplayFilter): Long`, returning the number of
  rows moved. Nothing later consumes it; this is the last code task.

- [ ] **Step 1: Write the failing repository tests**

```kotlin
@Test
fun `replay moves a sent row back to pending and resets the attempt`() = runBlocking {
    val id = insertOutboxRowCreatedSecondsAgo(seconds = 60)
    repository.markSent(id)

    val moved = repository.replay(ReplayFilter(ids = listOf(id)))

    assertEquals(1L, moved)
    val row = repository.findById(id)
    assertEquals("pending", row?.state)
    assertEquals(0, row?.attempt)
    assertNull(row?.lastError)
}

@Test
fun `replay leaves a processing row alone`() = runBlocking {
    val id = insertOutboxRowCreatedSecondsAgo(seconds = 60)
    repository.claimBatch(batchSize = 10, leaseMs = 30_000)

    val moved = repository.replay(ReplayFilter(ids = listOf(id)))

    // The relay owns a row in state 'processing'. Replay must never take it.
    assertEquals(0L, moved)
}

@Test
fun `replay selects a time range`() = runBlocking {
    val old = insertOutboxRowCreatedSecondsAgo(seconds = 3600)
    val recent = insertOutboxRowCreatedSecondsAgo(seconds = 10)
    repository.markSent(old)
    repository.markSent(recent)

    val moved = repository.replay(ReplayFilter(createdAfter = Clock.System.now().minus(60.seconds)))

    assertEquals(1L, moved)
    assertEquals("sent", repository.findById(old)?.state)
}
```

Confirm the real names of `findById`, `markSent` and `claimBatch` on the repository before you use
them. The previous phase's brief named methods that did not exist.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :postgres:test --tests '*Outbox*'`
Expected: FAIL to compile, because `ReplayFilter` and `replay` do not exist.

- [ ] **Step 3: Add the filter and the statement**

In `app/src/main/kotlin/dto/ReplayDto.kt`:

```kotlin
/**
 * Selects the rows that a replay moves. F-096.
 *
 * Every field is optional on its own, and a filter with no field at all is refused by the route,
 * because a replay of every row is never an accident.
 */
@Serializable
data class ReplayFilter(
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val source: String? = null,
    val destination: String? = null,
    val ids: List<@Serializable(with = UUIDSerializer::class) UUID>? = null
) {
    /** True when the filter selects nothing in particular. */
    fun isEmpty(): Boolean =
        createdAfter == null && createdBefore == null && source == null &&
            destination == null && ids.isNullOrEmpty()
}

@Serializable
data class ReplayResponse(val moved: Long)
```

Add `suspend fun replay(filter: ReplayFilter): Long` to the interface and implement it in all four
repositories. The statement sets `state = 'pending'`, `attempt = 0`, `last_error = NULL` and
`scheduled_at` to now, and its WHERE clause carries `state IN ('sent', 'dead')` ALWAYS, so a row in
`pending` or `processing` can never move whatever the filter says.

- [ ] **Step 4: Run the repository tests and confirm they pass**

- [ ] **Step 5: Write the failing route tests**

```kotlin
@Test
fun `a replay with no filter answers 400`() = testApplication {
    val response = client.post("/admin/replay") {
        header(HttpHeaders.Authorization, adminCredential)
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test
fun `an unauthenticated replay answers 401`() = testApplication {
    val response = client.post("/admin/replay") {
        contentType(ContentType.Application.Json)
        setBody("""{"source":"orders"}""")
    }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
}

@Test
fun `a replay answers the count that moved`() = testApplication {
    val first = insertOutboxMessage(topic = "orders.created", payload = """{}""", source = "orders")
    val second = insertOutboxMessage(topic = "orders.paid", payload = """{}""", source = "orders")
    val claimed = insertOutboxMessage(topic = "orders.held", payload = """{}""", source = "orders")
    repository.markSent(first)
    repository.markSent(second)
    repository.claimBatch(batchSize = 10, leaseMs = 30_000)

    val response = client.post("/admin/replay") {
        header(HttpHeaders.Authorization, adminCredential)
        contentType(ContentType.Application.Json)
        setBody("""{"source":"orders"}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    // Two sent rows move. The claimed row is in state 'processing', so it must not.
    assertEquals(2L, Json.decodeFromString<ReplayResponse>(response.bodyAsText()).moved)
    assertEquals("processing", repository.findById(claimed)?.state)
}
```

Follow the existing admin route test class for the credential helper and the application setup.
Read it before you write these.

- [ ] **Step 6: Add the route**

In `AdminRoutes.kt`, inside the existing `route("/admin")` block so it inherits the guard:

```kotlin
            post("/replay") {
                val filter = call.receive<ReplayFilter>()
                if (filter.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("A replay needs at least one filter."))
                    return@post
                }
                val moved = outboxRepository.replay(filter)
                log.info("Replay moved {} rows. Filter: {}", moved, filter)
                call.respond(ReplayResponse(moved))
            }
```

Use the error response type the file already uses. The log line is required by the finding: the
response reports the count before an operator can lose it, and the log records the filter.

- [ ] **Step 7: Prove the guard covers the new route**

The 401 test is not enough on its own, because it would also pass if the route did not exist. Add
an assertion that an AUTHENTICATED request to the same path answers something other than 401, so
the test distinguishes "guarded" from "absent".

- [ ] **Step 8: Run the gates and commit**

```bash
git add app core postgres sqlserver
git commit -m "feat(F-096): add POST /admin/replay to move sent rows back to pending"
```

---

## Task 4: The queue-not-found error names its own fix (F-097)

**Files:**
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt`
- Modify: `config/src/main/kotlin/QueueBoxConfig.kt`
- Modify: `app/src/main/kotlin/App.kt`
- Test: `rabbitmq/src/test/kotlin/` and `app/src/test/kotlin/`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the source setting `declareQueue: Boolean = false`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a missing queue names the cause, the asymmetry and the setting`() = runBlocking {
    val consumer = consumerFor(queue = "no-such-queue-${UUID.randomUUID()}")

    val error = assertFailsWith<Exception> { consumer.start() }

    val message = error.message!!
    assertTrue(message.contains("does not exist"), message)
    assertTrue(message.contains("does not declare"), message)
    assertTrue(message.contains("declareQueue"), message)
}

@Test
fun `declareQueue true creates the queue before the consumer starts`() = runBlocking {
    val queue = "declared-${UUID.randomUUID()}"
    val consumer = consumerFor(queue = queue, declareQueue = true)

    consumer.start()

    assertTrue(queueExists(queue))
}
```

Write `queueExists` in the test class using the AMQP client the file already imports.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :rabbitmq:test --tests '*Consumer*'` with a timeout you set.
Expected: the first fails because the raw broker text carries none of the three phrases.

- [ ] **Step 3: Catch and re-raise**

Catch the queue-not-found failure where the consumer opens its channel, and raise an error whose
message states three things in plain sentences: the queue does not exist; QueueBox does not declare
a source queue; and `declareQueue: true` on the source changes that. Name the queue and the source.

- [ ] **Step 4: Add the setting**

Add `declareQueue: Boolean = false` to the source configuration, carry it through `App.kt` to the
consumer config, and declare a durable queue before consuming when it is true. The default stays
false, because a declaration can mask a typo by creating an empty queue that never receives a
message. Put that reason in a comment.

- [ ] **Step 5: Write the reachability test**

A new configuration field needs a test from YAML text through to the object that consumes it. Phase
8 found a whole feature unreachable because every test built domain objects directly. Follow
`app/src/test/kotlin/DestinationFromYamlTest.kt`: parse a source with `declareQueue: true` and
assert it arrives on the consumer configuration. Then prove it bites: delete the wiring line,
confirm the test fails, restore it, confirm it passes, and confirm `git diff` is clean.

- [ ] **Step 6: Document it**

Add `declareQueue` to `docs/configuration.md` beside the other source settings, with its default
and the reason the default is false.

- [ ] **Step 7: Run the gates and commit**

```bash
git add rabbitmq config app docs
git commit -m "fix(F-097): name the cause and the fix when a source queue does not exist"
```

---

## Phase exit

- [ ] `./gradlew check` passes.
- [ ] `./gradlew ktlintCheck detekt` passes.
- [ ] Both age gauges report seconds, read zero on an empty table, and cost no query at scrape time.
- [ ] `POST /admin/replay` moves `sent` and `dead` rows only, resets the attempt, refuses an empty
      filter, and answers 401 without a credential.
- [ ] A missing source queue names the cause, the asymmetry and the setting.
- [ ] `declareQueue` is reachable from YAML and the reachability test bites.
- [ ] `docs/build/STATUS.md` records the phase and names the commits.

## Carried from earlier phases

- [ ] Restore the sentence in `docs/delivery-semantics.md` stating that QueueBox preserves no order
      between two different aggregates, marked as stated but not proven.
- [ ] Add a unit test for `clients/typescript/src/connections.ts`, whose cleanup rollback is wrapped
      in `.catch(() => undefined)` with nothing asserting it.
- [ ] Triage the load-sensitive test family as one finding rather than case by case:
      `E2EShutdownTest`, `TransformEngineCacheTest` and `InboxRepositoryTest` all fail under load
      and pass in isolation.
