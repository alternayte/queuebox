# Phase 7 — Stop the Bleeding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the three blocker defects that an adopter hit, and state the ordering guarantee that they had to learn by experiment.

**Architecture:** Three separate code paths change. The RabbitMQ inbox consumer gains the
`storeUnparsable` path that the Kafka consumer and the NATS consumer already have. The pull claim
statement gains a rule that reserves one in-flight message per aggregate, written once in
`examples/pull/sql` and then rendered by each of the three client libraries. The concurrency
default of each client library becomes 1. One documentation section states the resulting guarantee,
and a test proves each sentence of it.

**Tech Stack:** Kotlin, Gradle, Testcontainers, JUnit 5. C# 12, .NET 8, xUnit. Go 1.22. TypeScript,
Vitest. PostgreSQL 16 and SQL Server 2022.

**Spec:** `docs/superpowers/specs/2026-09-08-adoption-work-order-design.md`, findings F-086 to
F-089.

## Global Constraints

- The SQL source of truth is `examples/pull/sql`. A client library renders that text and changes
  only the identifiers. A change to a claim statement changes the example file FIRST, then the
  three libraries. `clients/contract-tests.md` names the behaviour that every library proves.
- Three client libraries exist and all three carry every client finding: `clients/csharp`,
  `clients/go`, `clients/typescript`. The spec names only C#, because the adopter uses C#. The rule
  is the same in all three.
- Every client behaviour runs against a real PostgreSQL AND a real SQL Server, in containers. A
  library that passes against one dialect only is not done.
- Write the test first. Run it. Confirm that it fails for the right reason. Then write the fix.
- Reference the finding ID in every commit message, for example `F-086`.
- The dead row must be written in ONE transaction. `hardening-doc.md` records the defect that a
  store in state `pending` followed by a mark dead produced. Never rebuild that shape.
- Prose follows ASD-STE100. No contraction, no `-ing` form outside a technical name, active voice,
  and `must` or `can` rather than `should` or `may`.

---

## File Structure

**Kotlin, F-086:**
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt` — add `parsePayload` and `storeUnparsable`,
  and call them from `processMessage`.
- Test: `rabbitmq/src/test/kotlin/RabbitConsumerIntegrationTest.kt` — add three tests.

**SQL, F-087:**
- Modify: `examples/pull/sql/postgresql/claim.sql` — the canonical statement.
- Modify: `examples/pull/sql/sqlserver/claim.sql` — the canonical statement.
- Modify: `clients/contract-tests.md` — add item 18.
- Modify: `clients/csharp/src/QueueBox.Inbox/InboxSql.cs` — render the new statement.
- Modify: `clients/go/sql.go` — render the new statement.
- Modify: `clients/typescript/src/sql.ts` — render the new statement.

**Concurrency, F-088:**
- Modify: `clients/csharp/src/QueueBox.Inbox/InboxOptions.cs:19` and `:40`.
- Modify: `clients/go/options.go:107-109`.
- Modify: `clients/typescript/src/options.ts:89`.
- Modify: `CHANGELOG.md`.

**Documentation, F-089:**
- Modify: `docs/delivery-semantics.md` — one new section.
- Test: `app/src/test/kotlin/e2e/OrderingGuaranteeTest.kt` — one test per stated sentence.

---

## Task 1: The RabbitMQ consumer stores an unparsable body dead (F-086)

**Files:**
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt:179` and around `:386`
- Test: `rabbitmq/src/test/kotlin/RabbitConsumerIntegrationTest.kt`

**Interfaces:**
- Consumes: the existing `storeRejected(envelope, message, reason)` at `RabbitConsumer.kt:276`,
  which writes state `dead` in one transaction and acknowledges. The existing
  `bodyDigest(body: ByteArray): String` at `:386`. The existing `storeDeadMessage` callback.
- Produces: `private fun parsePayload(body: ByteArray): JsonElement?` and
  `private suspend fun storeUnparsable(envelope: Envelope, body: ByteArray)`.

- [ ] **Step 1: Write the failing test**

Add to `rabbitmq/src/test/kotlin/RabbitConsumerIntegrationTest.kt`. Follow the container setup and
the helper names that the existing tests in that file already use.

```kotlin
@Test
fun `a body that is not JSON becomes one dead row and one acknowledgement`() = runBlocking {
    val source = "poison-source"
    startConsumer(source)

    publish(source, body = "not json".toByteArray())

    // The row must appear, and it must never be claimable.
    val row = awaitSingleInboxRow(source)
    assertEquals(MessageState.DEAD, row.state)
    assertEquals("not json", row.payload.jsonObject["raw"]!!.jsonPrimitive.content)

    // The broker must hold nothing. A requeue would leave the message here.
    assertEquals(0, queueDepth(source))
}

@Test
fun `a body that is not JSON is delivered exactly once`() = runBlocking {
    val source = "poison-once-source"
    val deliveries = AtomicInteger(0)
    startConsumer(source, onDelivery = { deliveries.incrementAndGet() })

    publish(source, body = "not json".toByteArray())
    awaitSingleInboxRow(source)

    // A hot loop shows here. Before the fix this number grows without bound.
    delay(2_000)
    assertEquals(1, deliveries.get())
}

@Test
fun `a storage failure still requeues the message`() = runBlocking {
    val source = "transient-source"
    startConsumer(source, storeResult = InboxResult.Error("the database is unreachable"))

    publish(source, body = """{"id":"e1"}""".toByteArray())

    // A failure to reach the database is transient. The broker must keep the message.
    awaitQueueDepth(source, expected = 1)
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :rabbitmq:test --tests '*RabbitConsumerIntegrationTest*'`

Expected: the first two tests FAIL. The first fails because no row exists at all, or because the
row is not `DEAD`. The second fails with a delivery count far above 1, because the consumer
requeues in a loop. The third test PASSES already, and it is there to prove that the fix does not
break the transient path.

- [ ] **Step 3: Add the parse function that returns null instead of a throw**

In `RabbitConsumer.kt`, beside `bodyDigest`:

```kotlin
/**
 * Parses the body, and returns null when the body is not JSON.
 *
 * The Kafka consumer carries the same function. A throw from the parse used to reach the
 * catch-all of `processMessage`, which requeued the delivery, so a body that never parses
 * looped at broker speed.
 */
private fun parsePayload(body: ByteArray): JsonElement? = try {
    json.parseToJsonElement(body.toString(Charsets.UTF_8))
} catch (e: SerializationException) {
    log.debug("The body is not JSON. Reason: {}", ErrorSanitizer.sanitize(e))
    null
}
```

- [ ] **Step 4: Add the dead path**

```kotlin
/**
 * A body that is not JSON cannot become an inbox payload.
 *
 * Nothing downstream can read it, and a requeue returns it at once, so the consumer spins at
 * broker speed. The body is preserved as a string inside a JSON object, the row is stored dead,
 * and the delivery is acknowledged. An operator still sees what arrived.
 */
private suspend fun storeUnparsable(envelope: Envelope, properties: AMQP.BasicProperties, body: ByteArray) {
    val message = InboxMessage(
        consumption = config.consumption,
        id = UUID.randomUUID(),
        source = config.sourceName,
        idempotencyKey = bodyDigest(body),
        payload = JsonObject(mapOf("raw" to JsonPrimitive(body.decodeToString()))),
        correlationId = extractCorrelationId(properties)
    )
    storeRejected(envelope, message, "the body is not JSON")
}
```

`storeRejected` already writes state `dead` in one transaction, acknowledges on success, and
requeues when the store itself fails. Reuse it rather than repeat it.

- [ ] **Step 5: Call the new path from `processMessage`**

Replace the first statement of the `try` block at `RabbitConsumer.kt:179`.

Before:

```kotlin
val payload = json.parseToJsonElement(body.toString(Charsets.UTF_8))
```

After:

```kotlin
val payload = parsePayload(body) ?: return storeUnparsable(envelope, properties, body)
```

- [ ] **Step 6: Record the reason on the metric**

`storeRejected` records `InboxRejectionReason.TRANSFORM_FAILED`. That is wrong for this path. Give
`storeRejected` a reason parameter, and pass `InboxRejectionReason.EXTRACTION_FAILED` from
`storeUnparsable`, which is the value that `KafkaInboxConsumer.kt:300` already uses for the same
event. Keep `TRANSFORM_FAILED` as the default, so the transform call site does not change.

```kotlin
private suspend fun storeRejected(
    envelope: Envelope,
    message: InboxMessage,
    reason: String,
    rejectionReason: InboxRejectionReason = InboxRejectionReason.TRANSFORM_FAILED
) {
```

Change the two `metricsCollector?.recordInboxRejection(InboxRejectionReason.TRANSFORM_FAILED)`
calls inside it to `metricsCollector?.recordInboxRejection(rejectionReason)`.

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `./gradlew :rabbitmq:test --tests '*RabbitConsumerIntegrationTest*'`
Expected: PASS, all three.

- [ ] **Step 8: Run the whole module**

Run: `./gradlew :rabbitmq:check`
Expected: PASS. The coverage gate must stay green.

- [ ] **Step 9: Commit**

```bash
git add rabbitmq/src/main/kotlin/RabbitConsumer.kt rabbitmq/src/test/kotlin/RabbitConsumerIntegrationTest.kt
git commit -m "fix(F-086): store an unparsable RabbitMQ body dead instead of a requeue loop"
```

---

## Task 2: Prove that the head-row claim removes the race (F-087)

The rule needs no new locking primitive. The naive statement races because it applies `LIMIT`
before it deduplicates by aggregate, so two workers pick DIFFERENT rows of one aggregate. When the
statement picks only the head row per aggregate, both workers pick the SAME row, and the ordinary
row lock serializes them. A worker that loses the lock falls through to nothing, because a sibling
row was never its candidate.

The ordering must be a TOTAL order. `ORDER BY scheduled_at, created_at` can tie, and a tie lets two
workers compute different heads. The statement orders by `scheduled_at, created_at, id`.

This task proves the claim against both dialects. It chooses nothing.

**Files:**
- Create: `docs/build/spikes/2026-09-08-aggregate-reservation.md`
- Create: throwaway SQL under the scratchpad directory. Keep none of it.

**Interfaces:**
- Produces: one verified statement per dialect, and the transcript. Task 3 copies both into
  `examples/pull/sql`.

- [ ] **Step 1: Reproduce the race**

Against a real PostgreSQL 16 container, create the inbox table with five pending rows that share
one `aggregate_id`. Run this in two concurrent sessions:

```sql
BEGIN;
WITH candidates AS (
    SELECT id FROM inbox
    WHERE consumption = 'pull' AND source = 's'
      AND state = 'pending' AND scheduled_at <= clock_timestamp()
      AND (aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = inbox.aggregate_id
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
    ORDER BY scheduled_at, created_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target SET state = 'processing'
FROM candidates WHERE target.id = candidates.id
RETURNING target.id;
SELECT pg_sleep(3);
COMMIT;
```

Expected: both sessions return a row of the same aggregate. Record the transcript. This is the
defect, and the next step must remove it.

- [ ] **Step 2: Verify the head-row statement on PostgreSQL**

```sql
WITH ready AS (
    SELECT id, aggregate_id, scheduled_at, created_at,
           row_number() OVER (
               PARTITION BY COALESCE(aggregate_id, id::text)
               ORDER BY scheduled_at, created_at, id
           ) AS rn
    FROM inbox
    WHERE consumption = 'pull' AND source = :source
      AND ((state = 'pending' AND scheduled_at <= clock_timestamp())
        OR (state = 'processing' AND lease_expires_at <= clock_timestamp()))
      AND (aggregate_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM inbox AS busy
            WHERE busy.aggregate_id = inbox.aggregate_id
              AND busy.consumption = 'pull'
              AND busy.state = 'processing'
              AND busy.lease_expires_at > clock_timestamp()))
),
picked AS (
    SELECT id FROM ready WHERE rn = 1
    ORDER BY scheduled_at, created_at, id
    LIMIT :batch
),
locked AS (
    SELECT i.id FROM inbox AS i
    JOIN picked AS p ON i.id = p.id
    FOR UPDATE SKIP LOCKED
)
UPDATE inbox AS target
SET state = 'processing', claim_token = gen_random_uuid(), claimed_at = clock_timestamp(),
    lease_expires_at = clock_timestamp() + :lease_ms * INTERVAL '1 millisecond'
FROM locked WHERE target.id = locked.id
RETURNING target.*;
```

The `locked` CTE selects from the base table `inbox`, not from a CTE output. That distinction is
what finding F-001 of `hardening-doc.md` turned on, so do not collapse it.

Run the two-session reproduction against it. Expected: the second session returns zero rows.

Run four more arrangements, and record each result:

1. Two aggregates, one row each. Expected: one claim returns both rows.
2. One aggregate whose head row is `processing` with an EXPIRED lease. Expected: the claim
   reclaims that row.
3. One aggregate whose head row is `processing` with a LIVE lease, and a pending sibling.
   Expected: the claim returns nothing for that aggregate.
4. Four rows with a NULL `aggregate_id`. Expected: one claim returns all four, because a null
   aggregate takes part in no ordering.

- [ ] **Step 3: Verify the SQL Server form**

Write the same statement with `ROW_NUMBER()`, `TOP (@batch)`, and `WITH (UPDLOCK, READPAST,
ROWLOCK)` on the base table. Run every arrangement of Step 1 and Step 2 against a real SQL Server
2022 container. The SQL Server locking hints are not the same primitive as `FOR UPDATE SKIP
LOCKED`, so nothing here is assumed from the PostgreSQL result.

- [ ] **Step 4: Measure the cost**

Run `EXPLAIN (ANALYZE, BUFFERS)` on the PostgreSQL statement with 100,000 rows and 10,000 distinct
aggregates. Record the plan and the time. Confirm that the `NOT EXISTS` uses
`idx_inbox_aggregate_state`. If it does not, record what index it needs, and add that index in
Task 3.

- [ ] **Step 5: Write the report**

Write `docs/build/spikes/2026-09-08-aggregate-reservation.md` with four sections: the reproduction
transcript, the PostgreSQL result over all five arrangements, the SQL Server result over the same
five, and the measured cost. State any arrangement that failed, and stop rather than proceed.

- [ ] **Step 6: Commit**

```bash
git add docs/build/spikes/2026-09-08-aggregate-reservation.md
git commit -m "test(F-087): prove the head-row claim removes the aggregate race on both dialects"
```

---

## Task 3: The canonical claim statement reserves one message per aggregate (F-087)

**Files:**
- Modify: `examples/pull/sql/postgresql/claim.sql`
- Modify: `examples/pull/sql/sqlserver/claim.sql`
- Modify: `clients/contract-tests.md`

**Interfaces:**
- Consumes: the statement that Task 2 verified against both dialects.
- Produces: the canonical text that Tasks 4, 5 and 6 render. The parameter names stay `:source`,
  `:batch` and `:lease_ms`, so no library changes its parameter binding.

- [ ] **Step 1: Add contract test item 18**

In `clients/contract-tests.md`, add a row to the table:

```markdown
| 18 | Hold at most one message per aggregate in flight | Fill the table with four messages of one aggregate and four of another. Run two workers. Assert that no two handler windows of one aggregate overlap, and that the two aggregates DO overlap. |
```

Add one sentence below the table: `Item 18 comes from finding F-087.`

- [ ] **Step 2: Write the accepted statement into the PostgreSQL example**

Replace the whole of `examples/pull/sql/postgresql/claim.sql` with the accepted statement. Add a
comment block above it that states the rule in two sentences:

```sql
-- The claim takes at most one message per aggregate. A row whose aggregate already holds a
-- message in state 'processing' under a live lease is not a candidate, and two rows of one
-- aggregate never leave one claim together.
```

- [ ] **Step 3: Write the SQL Server example**

Replace `examples/pull/sql/sqlserver/claim.sql` with the SQL Server form of the same rule, carrying
the same comment block. Keep `WITH (UPDLOCK, READPAST, ROWLOCK)` on the base table, because that is
the SQL Server equivalent of `FOR UPDATE SKIP LOCKED` and the existing statement already relies on
it.

- [ ] **Step 4: Verify both statements by hand**

Run each statement against its container with a table of eight rows, four per aggregate, from two
concurrent sessions. Expected: each session returns rows of at most one aggregate, and the two
sessions return different aggregates.

- [ ] **Step 5: Commit**

```bash
git add examples/pull/sql clients/contract-tests.md
git commit -m "feat(F-087): reserve one in-flight message per aggregate in the canonical claim"
```

---

## Task 4: The C# client renders the new claim (F-087)

**Files:**
- Modify: `clients/csharp/src/QueueBox.Inbox/InboxSql.cs:52-77` and `:105-125`
- Test: `clients/csharp/tests/QueueBox.Inbox.IntegrationTests/`

**Interfaces:**
- Consumes: the canonical text from Task 3.
- Produces: no signature change. `InboxSql.For(dialect, schema)` keeps its shape, and `Claim` keeps
  its three parameters.

- [ ] **Step 1: Write the failing integration test**

Add `AggregateReservationTests.cs` to the integration test project. Write it for both dialects
through the existing dialect fixture that the other integration tests use.

```csharp
[Theory]
[MemberData(nameof(Dialects))]
public async Task One_aggregate_never_runs_two_handlers_at_one_time(SqlDialect dialect)
{
    await using var db = await Fixture.CreateAsync(dialect);
    await db.SeedPendingAsync(source: "s", aggregateId: "agg-1", count: 4);

    var windows = new ConcurrentBag<(DateTimeOffset Start, DateTimeOffset End)>();
    async Task Handler(InboxMessage m, DbTransaction tx, CancellationToken ct)
    {
        var start = DateTimeOffset.UtcNow;
        await Task.Delay(200, ct);
        windows.Add((start, DateTimeOffset.UtcNow));
    }

    await db.RunWorkersAsync(count: 2, handler: Handler, untilProcessed: 4);

    var ordered = windows.OrderBy(w => w.Start).ToList();
    for (var i = 1; i < ordered.Count; i++)
    {
        Assert.True(ordered[i].Start >= ordered[i - 1].End,
            $"Handler {i} started at {ordered[i].Start}, before handler {i - 1} ended at {ordered[i - 1].End}.");
    }
}

[Theory]
[MemberData(nameof(Dialects))]
public async Task Two_aggregates_do_run_at_one_time(SqlDialect dialect)
{
    await using var db = await Fixture.CreateAsync(dialect);
    await db.SeedPendingAsync(source: "s", aggregateId: "agg-1", count: 2);
    await db.SeedPendingAsync(source: "s", aggregateId: "agg-2", count: 2);

    var inFlight = 0;
    var peak = 0;
    async Task Handler(InboxMessage m, DbTransaction tx, CancellationToken ct)
    {
        peak = Math.Max(peak, Interlocked.Increment(ref inFlight));
        await Task.Delay(200, ct);
        Interlocked.Decrement(ref inFlight);
    }

    await db.RunWorkersAsync(count: 2, handler: Handler, untilProcessed: 4);

    // The fix must not serialise the whole source.
    Assert.Equal(2, peak);
}
```

If `SeedPendingAsync` or `RunWorkersAsync` does not exist in the fixture, write it in this task.
Keep it in the fixture class, not in the test file, so Tasks 5 and 6 can copy the shape.

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `dotnet test clients/csharp/QueueBox.Inbox.slnx --filter AggregateReservation`
Expected: the first test FAILS with overlapping windows. The second test PASSES already.

- [ ] **Step 3: Render the new statement**

In `InboxSql.PostgreSql` and `InboxSql.SqlServer`, replace the `claim` string with the accepted
text from Task 3, with every identifier wrapped in the existing `Q` helper. Change nothing else.
The `fence` string, `renew`, `complete`, `retry` and `dead` stay exactly as they are.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `dotnet test clients/csharp/QueueBox.Inbox.slnx --filter AggregateReservation`
Expected: PASS, both dialects, both tests.

- [ ] **Step 5: Run the whole suite**

Run: `dotnet test clients/csharp/QueueBox.Inbox.slnx`
Expected: PASS. Contract items 5, 8 and 11 exercise the claim, so a regression shows here.

- [ ] **Step 6: Commit**

```bash
git add clients/csharp
git commit -m "fix(F-087): reserve one in-flight message per aggregate in the C# claim"
```

---

## Task 5: The Go client renders the new claim (F-087)

**Files:**
- Modify: `clients/go/sql.go`
- Test: `clients/go/contract/`

**Interfaces:**
- Consumes: the canonical text from Task 3.
- Produces: no exported signature change.

- [ ] **Step 1: Write the failing contract test**

Add `aggregate_reservation_test.go` under `clients/go/contract/`, in the shape of the existing
contract tests in that directory, and run it against both dialects through the existing fixture.

```go
func TestOneAggregateNeverRunsTwoHandlers(t *testing.T) {
	forEachDialect(t, func(t *testing.T, db *fixture) {
		db.seedPending(t, "s", "agg-1", 4)

		var mu sync.Mutex
		var windows [][2]time.Time

		handler := func(ctx context.Context, m inbox.Message, tx *sql.Tx) error {
			start := time.Now()
			time.Sleep(200 * time.Millisecond)
			mu.Lock()
			windows = append(windows, [2]time.Time{start, time.Now()})
			mu.Unlock()
			return nil
		}

		db.runWorkers(t, 2, handler, 4)

		sort.Slice(windows, func(i, j int) bool { return windows[i][0].Before(windows[j][0]) })
		for i := 1; i < len(windows); i++ {
			if windows[i][0].Before(windows[i-1][1]) {
				t.Fatalf("handler %d started at %v, before handler %d ended at %v",
					i, windows[i][0], i-1, windows[i-1][1])
			}
		}
	})
}

func TestTwoAggregatesDoRunAtOneTime(t *testing.T) {
	forEachDialect(t, func(t *testing.T, db *fixture) {
		db.seedPending(t, "s", "agg-1", 2)
		db.seedPending(t, "s", "agg-2", 2)

		var inFlight, peak int64
		handler := func(ctx context.Context, m inbox.Message, tx *sql.Tx) error {
			n := atomic.AddInt64(&inFlight, 1)
			for {
				p := atomic.LoadInt64(&peak)
				if n <= p || atomic.CompareAndSwapInt64(&peak, p, n) {
					break
				}
			}
			time.Sleep(200 * time.Millisecond)
			atomic.AddInt64(&inFlight, -1)
			return nil
		}

		db.runWorkers(t, 2, handler, 4)

		if peak != 2 {
			t.Fatalf("peak concurrency was %d, want 2", peak)
		}
	})
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `cd clients/go && go test ./contract -run AggregateNeverRuns -v`
Expected: FAIL with overlapping windows.

- [ ] **Step 3: Render the new statement**

In `clients/go/sql.go`, replace the claim text for both dialects with the accepted text from
Task 3, keeping the existing identifier quoting helper. Change nothing else.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `cd clients/go && go test ./contract -run 'Aggregate' -v`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `cd clients/go && go test ./...`
Expected: PASS. `sql_test.go` asserts the rendered text, so update its fixtures in this task.

- [ ] **Step 6: Commit**

```bash
git add clients/go
git commit -m "fix(F-087): reserve one in-flight message per aggregate in the Go claim"
```

---

## Task 6: The TypeScript client renders the new claim (F-087)

**Files:**
- Modify: `clients/typescript/src/sql.ts`
- Test: `clients/typescript/test/`

**Interfaces:**
- Consumes: the canonical text from Task 3.
- Produces: no exported signature change.

- [ ] **Step 1: Write the failing contract test**

Add `test/contract/aggregate-reservation.test.ts`, in the shape of the existing contract tests, and
run it against both dialects.

```typescript
describe.each(dialects)("aggregate reservation on %s", (dialect) => {
  it("never runs two handlers of one aggregate at one time", async () => {
    const db = await createFixture(dialect);
    await db.seedPending({ source: "s", aggregateId: "agg-1", count: 4 });

    const windows: Array<[number, number]> = [];
    const handler = async () => {
      const start = Date.now();
      await new Promise((r) => setTimeout(r, 200));
      windows.push([start, Date.now()]);
    };

    await db.runWorkers({ count: 2, handler, untilProcessed: 4 });

    windows.sort((a, b) => a[0] - b[0]);
    for (let i = 1; i < windows.length; i++) {
      expect(windows[i][0]).toBeGreaterThanOrEqual(windows[i - 1][1]);
    }
  });

  it("does run two aggregates at one time", async () => {
    const db = await createFixture(dialect);
    await db.seedPending({ source: "s", aggregateId: "agg-1", count: 2 });
    await db.seedPending({ source: "s", aggregateId: "agg-2", count: 2 });

    let inFlight = 0;
    let peak = 0;
    const handler = async () => {
      peak = Math.max(peak, ++inFlight);
      await new Promise((r) => setTimeout(r, 200));
      inFlight--;
    };

    await db.runWorkers({ count: 2, handler, untilProcessed: 4 });

    expect(peak).toBe(2);
  });
});
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `cd clients/typescript && npm test -- aggregate-reservation`
Expected: FAIL with overlapping windows.

- [ ] **Step 3: Render the new statement**

In `clients/typescript/src/sql.ts`, replace the claim text for both dialects with the accepted text
from Task 3, keeping the existing identifier quoting helper. Change nothing else.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `cd clients/typescript && npm test -- aggregate-reservation`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `cd clients/typescript && npm test`
Expected: PASS. `test/unit/sql.test.ts` asserts the rendered text, so update its fixtures in this
task.

- [ ] **Step 6: Commit**

```bash
git add clients/typescript
git commit -m "fix(F-087): reserve one in-flight message per aggregate in the TypeScript claim"
```

---

## Task 7: The concurrency default becomes 1 in all three clients (F-088)

**Files:**
- Modify: `clients/csharp/src/QueueBox.Inbox/InboxOptions.cs:19` and `:40`
- Modify: `clients/go/options.go:107-109`
- Modify: `clients/typescript/src/options.ts:89`
- Modify: `clients/csharp/README.md`, `clients/go/README.md`, `clients/typescript/README.md`
- Modify: `CHANGELOG.md`
- Test: `clients/csharp/tests/QueueBox.Inbox.Tests/`, `clients/go/options_test.go`,
  `clients/typescript/test/unit/options.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EffectiveConcurrency` in C#, `concurrency` in Go, `concurrency` in TypeScript. All
  three yield 1 when the caller sets nothing, and the caller's value when the caller sets one.

- [ ] **Step 1: Write the three failing unit tests**

C#, in the existing options test class:

```csharp
[Fact]
public void The_concurrency_default_is_one()
{
    var options = new InboxOptions { Source = "s" };
    Assert.Equal(1, options.EffectiveConcurrency);
}

[Fact]
public void An_explicit_concurrency_survives()
{
    var options = new InboxOptions { Source = "s", MaxConcurrency = 5 };
    Assert.Equal(5, options.EffectiveConcurrency);
}

[Fact]
public void The_concurrency_never_passes_the_batch()
{
    var options = new InboxOptions { Source = "s", BatchSize = 3, MaxConcurrency = 10 };
    Assert.Equal(3, options.EffectiveConcurrency);
}
```

Go, in `options_test.go`:

```go
func TestConcurrencyDefaultsToOne(t *testing.T) {
	resolved, _, _, _, err := Options{Source: "s"}.resolve()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}
	if resolved.concurrency != 1 {
		t.Fatalf("concurrency was %d, want 1", resolved.concurrency)
	}
}

func TestExplicitConcurrencySurvives(t *testing.T) {
	resolved, _, _, _, err := Options{Source: "s", MaxConcurrency: 5}.resolve()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}
	if resolved.concurrency != 5 {
		t.Fatalf("concurrency was %d, want 5", resolved.concurrency)
	}
}
```

Match the real name and the real signature of the resolve function in `options.go`. The names above
follow `options.go:66` and `options.go:107`.

TypeScript, in `test/unit/options.test.ts`:

```typescript
it("defaults the concurrency to one", () => {
  expect(resolveOptions({ source: "s" }).concurrency).toBe(1);
});

it("keeps an explicit concurrency", () => {
  expect(resolveOptions({ source: "s", maxConcurrency: 5 }).concurrency).toBe(5);
});
```

- [ ] **Step 2: Run the three suites to verify that the default tests fail**

Run: `dotnet test clients/csharp/QueueBox.Inbox.slnx --filter Concurrency`
Run: `cd clients/go && go test ./... -run Concurrency`
Run: `cd clients/typescript && npm test -- options`
Expected: the default test FAILS in each language with the value 10. The explicit tests PASS.

- [ ] **Step 3: Change the C# default**

`InboxOptions.cs:40`:

```csharp
    /// <summary>The concurrency the worker actually applies. The default is one.</summary>
    public int EffectiveConcurrency => Math.Min(MaxConcurrency ?? 1, BatchSize);
```

`InboxOptions.cs:15-19`, the documentation of the property:

```csharp
    /// <summary>
    /// The largest number of handlers that run at one time. It never passes the batch size.
    /// A null value means one, so a handler meets no sibling message unless the caller asks for
    /// parallelism. Set it above one only when the handler is safe against a concurrent sibling.
    /// </summary>
    public int? MaxConcurrency { get; init; }
```

- [ ] **Step 4: Change the Go default**

`options.go:107-109`:

```go
	// A zero value means one. A handler meets no sibling message unless the caller asks for
	// parallelism.
	concurrency := o.MaxConcurrency
	if concurrency == 0 {
		concurrency = 1
	}
```

Update the comment at `options.go:32-33` to state that the default is one.

- [ ] **Step 5: Change the TypeScript default**

`options.ts:89`:

```typescript
    concurrency: Math.min(options.maxConcurrency ?? 1, batchSize),
```

Update the documentation comment at `options.ts:20` to state that the default is one.

- [ ] **Step 6: Run the three suites to verify that they pass**

Run all three commands from Step 2.
Expected: PASS.

- [ ] **Step 7: Update the three README files**

In each client README, in the options table, state that the concurrency default is one and give the
one line that restores parallelism. For C#:

```csharp
var options = new InboxOptions { Source = "orders", MaxConcurrency = 10 };
```

State the condition in one sentence: `Raise it only when the handler is safe against a sibling
message of another aggregate.`

- [ ] **Step 8: Add the CHANGELOG entry**

Under `### Breaking changes` of the unreleased section:

```markdown
- The pull client concurrency default is now one, in all three client libraries. It was the batch
  size, which is ten, so ten handlers ran at one time and nothing in the API said so. A handler
  that reads a row and then writes it failed with a duplicate key error when it met a sibling
  message. To restore the old behaviour, set `MaxConcurrency` to the batch size. (F-088)
```

- [ ] **Step 9: Commit**

```bash
git add clients CHANGELOG.md
git commit -m "fix(F-088): default the pull client concurrency to one in all three clients"
```

---

## Task 8: State the ordering guarantee, and prove each sentence (F-089)

**Files:**
- Modify: `docs/delivery-semantics.md`
- Create: `app/src/test/kotlin/e2e/OrderingGuaranteeTest.kt`

**Interfaces:**
- Consumes: the behaviour that Tasks 1 to 7 produced. This task states it, and adds no behaviour.
- Produces: five named tests, one per stated sentence. The test name repeats the sentence, so a
  reader of the document can find the proof.

- [ ] **Step 1: Write the five failing tests**

Create `app/src/test/kotlin/e2e/OrderingGuaranteeTest.kt`, in the shape of the existing end to end
tests in that package.

```kotlin
/**
 * One test per sentence of the ordering section of `docs/delivery-semantics.md`.
 *
 * A guarantee without a test is a wish. The name of each test repeats the sentence it proves, so
 * a reader of the document can find the proof.
 */
class OrderingGuaranteeTest : E2ETestBase() {

    @Test
    fun `QueueBox delivers at least once`() = runBlocking {
        val server = startMockHttpServer()
        // The first attempt fails after the broker received the body, which is the crash window.
        server.setResponse(HttpStatusCode.InternalServerError)
        val id = insertOutboxMessage(topic = "t", payload = """{"n":1}""")

        awaitUntil { server.requestCount >= 1 }
        server.setResponse(HttpStatusCode.OK)

        // The message must arrive again. It must never vanish.
        assertTrue(awaitUntil { getOutboxMessageState(id) == "sent" })
        assertTrue(server.requestCount >= 2, "the message was delivered only once")
    }

    @Test
    fun `in push mode one aggregate holds at most one message in flight`() = runBlocking {
        repeat(4) { n ->
            insertInboxMessage(
                source = "s",
                idempotencyKey = "k$n",
                aggregateId = "agg-1",
                consumption = "push"
            )
        }
        startRelay()

        // The relay forwards one at a time, so the outbox never holds two rows of the aggregate
        // in state 'pending' or 'processing' at one time.
        val peak = observePeakInFlight(aggregateId = "agg-1", untilForwarded = 4)
        assertEquals(1, peak)

        // The order is the order of created_at.
        assertEquals(listOf("k0", "k1", "k2", "k3"), forwardedIdempotencyKeysInOrder())
    }

    @Test
    fun `in pull mode one aggregate holds at most one message in flight`() = runBlocking {
        repeat(4) { n ->
            insertInboxMessage(
                source = "s",
                idempotencyKey = "k$n",
                aggregateId = "agg-1",
                consumption = "pull"
            )
        }

        val repository = InboxRepository(ExposedTransactionRunner())
        val first = repository.claimPending(source = "s", batch = 10, leaseMs = 30_000)
        val second = repository.claimPending(source = "s", batch = 10, leaseMs = 30_000)

        // The first claim takes one row of the aggregate. The second takes none of it.
        assertEquals(1, first.size)
        assertEquals(0, second.count { it.aggregateId == "agg-1" })
    }

    @Test
    fun `QueueBox preserves no order between two aggregates`() = runBlocking {
        insertInboxMessage(source = "s", idempotencyKey = "a0", aggregateId = "agg-1", consumption = "pull")
        insertInboxMessage(source = "s", idempotencyKey = "b0", aggregateId = "agg-2", consumption = "pull")

        val repository = InboxRepository(ExposedTransactionRunner())
        val claimed = repository.claimPending(source = "s", batch = 10, leaseMs = 30_000)

        // Both aggregates leave one claim together. No reader can rely on an order between them.
        assertEquals(setOf("agg-1", "agg-2"), claimed.mapNotNull { it.aggregateId }.toSet())
    }

    @Test
    fun `a poller delivers in claim order and not in commit order`() = runBlocking {
        // Transaction A takes the lower identifier first, and commits last.
        val slow = openTransactionAndInsertOutbox(topic = "t", payload = """{"n":"slow"}""")
        val fast = insertOutboxMessage(topic = "t", payload = """{"n":"fast"}""")
        assertTrue(slow.id < fast, "the arrangement needs the slow row to hold the lower identifier")

        val server = startMockHttpServer()
        startPoller()
        assertTrue(awaitUntil { server.requestCount >= 1 })
        slow.commit()

        assertTrue(awaitUntil { server.requestCount >= 2 })

        // The later commit arrived first. Commit order is not claim order.
        assertEquals(listOf("fast", "slow"), server.receivedBodies.map { bodyName(it) })
    }
}
```

`openTransactionAndInsertOutbox`, `observePeakInFlight`, `forwardedIdempotencyKeysInOrder`,
`startRelay`, `startPoller` and `bodyName` do not exist yet. Write them in `E2ETestBase`, beside
the existing `insertOutboxMessage` and `awaitUntil`, so a later phase can reuse them. Confirm the
real signature of `InboxRepository.claimPending` before you write the pull tests, and match it.


- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :app:test --tests '*OrderingGuaranteeTest*'`
Expected: the five tests FAIL, because the helpers of Step 1 do not exist yet. Write the helpers in
`E2ETestBase`, then run again. Four must PASS. `in pull mode one aggregate holds at most one
message in flight` proves the fix of Task 3, so it passes only after Task 3 is merged.

- [ ] **Step 3: Write the documentation section**

Add to `docs/delivery-semantics.md`, in a section named `Order and the aggregate`:

```markdown
## Order and the aggregate

QueueBox delivers at least once. A crash between the publish and the mark sent delivers the
message a second time. Your consumer must tolerate a repeat, and the inbox pattern is how it
does that.

The `aggregate_id` column is the unit of order. It carries the same meaning in both consumption
modes.

- In push mode, one aggregate holds at most one message in flight. The relay forwards the
  messages of one aggregate one at a time, in the order of `created_at`.
- In pull mode, the same rule holds. The claim never returns a message whose aggregate already
  holds a message in state `processing`, and the rule holds across every worker instance,
  because it lives in the claim statement.
- QueueBox preserves no order between two different aggregates. Two aggregates progress at one
  time, by design.
- A row with no `aggregate_id` takes part in no ordering. It is delivered as soon as a worker is
  free.

One difference from a log-based capture tool deserves a plain statement. A poller delivers in
claim order, not in commit order. A row can take its identifier before another row and still
commit after it, so a reader that needs commit order must not derive it from the identifier.
Order inside one aggregate is the guarantee that QueueBox gives, and it is enough for the outbox
pattern, because one aggregate is one writer.

Each sentence above has a test. See `app/src/test/kotlin/e2e/OrderingGuaranteeTest.kt`.
```

- [ ] **Step 4: Run the whole check**

Run: `./gradlew check`
Expected: PASS. The document test harness from Phase 6 runs every code sample, and the coverage
gate must stay green.

- [ ] **Step 5: Commit**

```bash
git add docs/delivery-semantics.md app/src/test/kotlin/e2e/OrderingGuaranteeTest.kt
git commit -m "docs(F-089): state the ordering guarantee per consumption mode, with a test each"
```

---

## Phase exit

The phase is closed when all of the following produce their evidence:

- [ ] `./gradlew check` passes.
- [ ] `dotnet test clients/csharp/QueueBox.Inbox.slnx` passes.
- [ ] `cd clients/go && go test ./...` passes.
- [ ] `cd clients/typescript && npm test` passes.
- [ ] A body that is not JSON produces one dead row and one delivery.
- [ ] Two pull workers never hold two messages of one aggregate, in all three clients, on both
      dialects.
- [ ] The concurrency default is 1 in all three clients.
- [ ] `docs/build/STATUS.md` records the phase and names the commits.
