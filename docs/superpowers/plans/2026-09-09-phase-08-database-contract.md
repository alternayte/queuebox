# Phase 8 — The Database Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one destination serve many aggregate types, by giving the outbox row an
`aggregate_type` column and letting a destination render its exchange, topic or subject from the
row.

**Architecture:** The outbox table gains a nullable `aggregate_type` column. `RoutingKeyRenderer`
grows a row context so a template can read `aggregateType` and `key` as well as `topic` and the
payload. `MessageRouter` resolves the destination address per row and carries it in
`RoutingResult`, so a publisher receives a resolved address and stays simple. The RabbitMQ
publisher caches its exchange declarations per exchange name, because a per-row exchange must not
declare on every message. `StartupValidator` rejects a template that names an unknown field, and an
`exchangeFrom` that names a column outside the permitted set.

**Tech Stack:** Kotlin, Gradle, Exposed, Flyway, Testcontainers, JUnit 5. PostgreSQL 16 and
SQL Server 2022. RabbitMQ, Kafka, NATS.

**Spec:** `docs/superpowers/specs/2026-09-08-adoption-work-order-design.md`, findings F-090 to
F-093.

## Global Constraints

- The exit condition of EVERY task is the project's own gate commands, not a suite the implementer
  chooses: `./gradlew check` must pass, and so must `./gradlew ktlintCheck detekt`. Phase 7 shipped
  two red builds because tasks ran only their own module's tests. Run the gates.
- Run every test command in the FOREGROUND with an explicit timeout. Never in the background. Two
  Phase 7 agents lost time waiting on a background run.
- Write the test first. Run it. Confirm it fails FOR THE RIGHT REASON. Then fix. Then confirm it
  passes.
- For every test that guards a rule, ask: what edit to the production code would make this test
  fail? If no edit would, the test proves nothing. Phase 7 found three such tests, including a
  security guard that could not fail.
- A migration is additive and NEVER edits a shipped file. V8 is the highest on `main`, so this
  phase writes V9. Confirm V9 is free before you write it.
- `aggregate_type` is nullable. A row that does not set it must still publish.
- The permitted `exchangeFrom` columns are exactly `aggregate_type`, `topic` and `key`. Any other
  name fails the startup. This is a maintainer decision, not a default to widen.
- The push relay and the pull claim keep their different aggregate scoping. Phase 7 documented
  both. Do NOT unify them in this phase.
- Reference the finding ID in every commit message, for example `F-090`.
- Prose follows ASD-STE100: no contraction, no `-ing` form outside a technical name, active voice,
  and `must` or `can` rather than `should` or `may`. Prose only, never code or identifiers.
- Never run `git revert`, `git reset --hard`, `git checkout -- .`, `git restore`, or `git clean`.

---

## File Structure

**F-090, the column:**
- Create: `postgres/src/main/resources/db/postgresql/V9__add_aggregate_type.sql`
- Create: `sqlserver/src/main/resources/db/sqlserver/V9__add_aggregate_type.sql`
- Modify: `core/src/main/kotlin/OutboxMessage.kt` — add the field
- Modify: `postgres/src/main/kotlin/Tables.kt` — add the column
- Modify: `postgres/src/main/kotlin/OutboxRepository.kt` — read and write it

**The renderer context:**
- Modify: `outbox-service/src/main/kotlin/RoutingKeyRenderer.kt` — take a row context
- Modify: `outbox-service/src/main/kotlin/MessageRouter.kt` — resolve the address per row

**F-091, the address:**
- Modify: `core/src/main/kotlin/Destination.kt` — `exchangeFrom`, and the same for Kafka and NATS
- Modify: `rabbitmq/src/main/kotlin/RabbitPublisher.kt` — declare per exchange, from a cache
- Modify: `kafka/src/main/kotlin/KafkaPublisher.kt`, `nats/src/main/kotlin/NatsPublisher.kt`

**F-092, the validation:**
- Modify: `outbox-service/src/main/kotlin/StartupValidator.kt`

**F-093, the documentation:**
- Modify: `docs/integration.md`
- Test: `app/src/test/kotlin/docs/IntegrationDocSqlTest.kt`

---

## Task 1: The outbox row carries an aggregate type (F-090)

**Files:**
- Create: `postgres/src/main/resources/db/postgresql/V9__add_aggregate_type.sql`
- Create: `sqlserver/src/main/resources/db/sqlserver/V9__add_aggregate_type.sql`
- Modify: `core/src/main/kotlin/OutboxMessage.kt:11-27`
- Modify: `postgres/src/main/kotlin/Tables.kt` (the outbox table object)
- Modify: `postgres/src/main/kotlin/OutboxRepository.kt`
- Test: `postgres/src/test/kotlin/org/nxtspec/` and the SQL Server twin

**Interfaces:**
- Produces: `OutboxMessage.aggregateType: String?`, defaulting to null, and the column
  `aggregate_type` on the outbox table. Tasks 2 to 6 read it.

- [ ] **Step 1: Confirm V9 is free**

Run: `ls postgres/src/main/resources/db/postgresql/ sqlserver/src/main/resources/db/sqlserver/`
Expected: the highest file in each is `V8__add_pull_claim_indexes.sql`. If a V9 already exists,
STOP and report rather than overwrite it.

- [ ] **Step 2: Write the failing test**

Add to the existing outbox repository test class for each dialect. Follow the container setup the
neighbouring tests already use.

```kotlin
@Test
fun `an aggregate type round trips through the outbox row`() = runBlocking {
    val message = OutboxMessage(
        topic = "orders.created",
        payload = JsonObject(emptyMap()),
        aggregateType = "Task"
    )
    repository.insert(message)

    val stored = repository.findById(message.id)

    assertEquals("Task", stored?.aggregateType)
}

@Test
fun `a row with no aggregate type still publishes`() = runBlocking {
    // The column is nullable, so an existing writer that never sets it must not break.
    val message = OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()))
    repository.insert(message)

    val claimed = repository.claimPending(batch = 10, leaseMs = 30_000)

    assertTrue(claimed.any { it.id == message.id })
    assertNull(claimed.first { it.id == message.id }.aggregateType)
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `./gradlew :postgres:test --tests '*Outbox*' :sqlserver:test --tests '*Outbox*'`
Expected: FAIL to compile, because `OutboxMessage` has no `aggregateType` parameter. That is the
right reason.

- [ ] **Step 4: Write the two migrations**

`postgres/src/main/resources/db/postgresql/V9__add_aggregate_type.sql`:

```sql
-- F-090. A destination can render its exchange from the row, and it needs a field to render from.
-- Debezium reads `aggregatetype` for the same purpose. The column is nullable, so the migration is
-- additive and an existing writer that never sets it keeps working.
ALTER TABLE outbox ADD COLUMN aggregate_type VARCHAR(255);
```

`sqlserver/src/main/resources/db/sqlserver/V9__add_aggregate_type.sql`:

```sql
-- F-090. A destination can render its exchange from the row, and it needs a field to render from.
-- Debezium reads `aggregatetype` for the same purpose. The column is nullable, so the migration is
-- additive and an existing writer that never sets it keeps working.
ALTER TABLE outbox ADD aggregate_type NVARCHAR(255) NULL;
```

Add NO index. The claim does not filter on this column, and Phase 7 removed an index that was added
without measurement. If a later task proves an index is needed, it gets its own migration.

- [ ] **Step 5: Add the field to the domain type**

In `core/src/main/kotlin/OutboxMessage.kt`, after `key`:

```kotlin
    /** The aggregate type, which a destination template can render. F-090. */
    val aggregateType: String? = null,
```

Place it with a default so every existing construction site keeps compiling.

- [ ] **Step 6: Add the column and map it**

In `postgres/src/main/kotlin/Tables.kt`, in the outbox table object, beside `key`:

```kotlin
    val aggregateType: Column<String?> = varchar("aggregate_type", 255).nullable()
```

In `postgres/src/main/kotlin/OutboxRepository.kt`, set it on insert and read it in every row mapper.
Search the file for `key` and add `aggregateType` at each site, so no read path returns null by
accident.

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `./gradlew :postgres:test --tests '*Outbox*' :sqlserver:test --tests '*Outbox*'`
Expected: PASS, both dialects.

- [ ] **Step 8: Prove the migration applies to a POPULATED database**

The DoD requires this, and an empty database does not prove it.

```kotlin
@Test
fun `V9 applies to a populated outbox table`() = runBlocking {
    // Migrate to V8, insert rows, then migrate to V9 and assert the rows survive with a null
    // aggregate type.
}
```

Write the real body using the migrator the repository already exposes. Assert the row count before
and after, and that every pre-existing row has a null `aggregate_type`.

- [ ] **Step 9: Run the gates**

Run: `./gradlew check` and `./gradlew ktlintCheck detekt`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 10: Commit**

```bash
git add postgres sqlserver core
git commit -m "feat(F-090): add the nullable aggregate_type column to the outbox row"
```

---

## Task 2: A template can read the outbox row (F-091, groundwork)

**Files:**
- Modify: `outbox-service/src/main/kotlin/RoutingKeyRenderer.kt:18-45`
- Test: `outbox-service/src/test/kotlin/` the existing renderer test class

**Interfaces:**
- Consumes: `OutboxMessage.aggregateType` from Task 1.
- Produces: `RoutingKeyRenderer.render(template: String, row: RowContext): String` and
  `data class RowContext(val topic: String, val key: String?, val aggregateType: String?, val payload: JsonElement)`.
  Tasks 3, 4, 5 and 6 use both. The old three-argument `render` is kept as a deprecated overload
  that builds a `RowContext` with null `key` and null `aggregateType`, so no call site breaks in
  this task.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a template renders the aggregate type`() {
    val renderer = RoutingKeyRenderer()
    val row = RoutingKeyRenderer.RowContext(
        topic = "orders.created",
        key = "order-1",
        aggregateType = "Task",
        payload = JsonObject(emptyMap())
    )

    assertEquals("public.orders.Task.v1", renderer.render("public.orders.{{ aggregateType }}.v1", row))
}

@Test
fun `a template renders the key`() {
    val renderer = RoutingKeyRenderer()
    val row = RoutingKeyRenderer.RowContext("orders.created", "order-1", "Task", JsonObject(emptyMap()))

    assertEquals("order-1", renderer.render("{{ key }}", row))
}

@Test
fun `an unknown field still renders the default`() {
    // The existing behaviour. A template that names an unknown field renders the default value,
    // and Task 6 makes such a template fail the startup instead.
    val renderer = RoutingKeyRenderer(defaultValue = "")
    val row = RoutingKeyRenderer.RowContext("orders.created", null, null, JsonObject(emptyMap()))

    assertEquals("", renderer.render("{{ nosuchfield }}", row))
}

@Test
fun `a null aggregate type renders the default`() {
    val renderer = RoutingKeyRenderer(defaultValue = "")
    val row = RoutingKeyRenderer.RowContext("orders.created", null, null, JsonObject(emptyMap()))

    assertEquals("", renderer.render("{{ aggregateType }}", row))
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :outbox-service:test --tests '*RoutingKeyRenderer*'`
Expected: FAIL to compile, because `RowContext` does not exist.

- [ ] **Step 3: Add the context and the new field names**

In `RoutingKeyRenderer.kt`, add the type and extend `resolveField`:

```kotlin
    /** The fields of one outbox row that a template can read. F-091. */
    data class RowContext(
        val topic: String,
        val key: String?,
        val aggregateType: String?,
        val payload: JsonElement
    )

    fun render(template: String, row: RowContext): String =
        placeholderPattern.replace(template) { match ->
            resolveField(match.groupValues[1].trim(), row)
        }

    private fun resolveField(field: String, row: RowContext): String = when {
        field == "topic" -> row.topic
        field == "key" -> row.key ?: defaultValue
        field == "aggregateType" -> row.aggregateType ?: defaultValue
        field.startsWith("payload.") -> extractPayloadField(row.payload, field.removePrefix("payload.")) ?: defaultValue
        field.startsWith("data.") -> extractPayloadField(row.payload, field.removePrefix("data.")) ?: defaultValue
        else -> defaultValue
    }
```

Keep the existing three-argument `render(template, topic, payload)` as an overload that delegates
with null `key` and null `aggregateType`, so `MessageRouter` still compiles in this task.

Add the new field names to the class documentation comment, beside the existing list.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `./gradlew :outbox-service:test --tests '*RoutingKeyRenderer*'`
Expected: PASS.

- [ ] **Step 5: Run the gates and commit**

Run: `./gradlew check` and `./gradlew ktlintCheck detekt`

```bash
git add outbox-service
git commit -m "feat(F-091): let a template read the key and the aggregate type of a row"
```

---

## Task 3: The RabbitMQ exchange renders per row (F-091)

**Files:**
- Modify: `core/src/main/kotlin/Destination.kt:83-96`
- Modify: `outbox-service/src/main/kotlin/MessageRouter.kt:50-75`
- Modify: `rabbitmq/src/main/kotlin/RabbitPublisher.kt:129-142`
- Test: `rabbitmq/src/test/kotlin/` and `outbox-service/src/test/kotlin/`

**Interfaces:**
- Consumes: `RoutingKeyRenderer.RowContext` and the two-argument `render` from Task 2.
- Produces: `RoutingResult.resolvedAddress: String`, the exchange, topic or subject that the
  publisher must use for THIS row. Tasks 4 and 5 set the same field. A publisher reads
  `resolvedAddress` and never reads `Destination.exchange` directly again.

- [ ] **Step 1: Write the failing tests**

```kotlin
// Add to rabbitmq/src/test/kotlin/RabbitPublisherIntegrationTest.kt, which already starts the
// container and carries the bindQueue helper at line 62.

@Test
fun `two aggregate types reach two exchanges through one destination`() = runBlocking {
    val destination = Destination.RabbitMQ(
        name = "per-row",
        url = amqpUrl,
        exchange = "public.orders.{{ aggregateType }}.v1",
        exchangeType = "topic",
        routingKeyTemplate = "{{ topic }}"
    )
    bindQueue("public.orders.Task.v1", "topic", "orders.created")
    bindQueue("public.orders.Order.v1", "topic", "orders.created")

    publisher.publish(destination, OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()), aggregateType = "Task"), "orders.created")
    publisher.publish(destination, OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()), aggregateType = "Order"), "orders.created")

    assertEquals(1, messageCount("public.orders.Task.v1"))
    assertEquals(1, messageCount("public.orders.Order.v1"))
}

@Test
fun `an empty rendered exchange fails the row and publishes nothing`() = runBlocking {
    val destination = Destination.RabbitMQ(
        name = "empty-render",
        url = amqpUrl,
        exchange = "{{ aggregateType }}",
        exchangeType = "topic",
        routingKeyTemplate = "{{ topic }}"
    )
    // The row sets no aggregate type, so the template renders an empty string.
    val row = OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()))

    val failure = assertFailsWith<Exception> {
        publisher.publish(destination, row, "orders.created")
    }

    // It must name the destination and the template, and it must never reach the default exchange.
    assertTrue(failure.message!!.contains("empty-render"))
    assertEquals(0, messageCount(""))
}

@Test
fun `the exchange is declared once per exchange name`() = runBlocking {
    val destination = Destination.RabbitMQ(
        name = "declare-once",
        url = amqpUrl,
        exchange = "public.orders.{{ aggregateType }}.v1",
        exchangeType = "topic",
        routingKeyTemplate = "{{ topic }}"
    )
    bindQueue("public.orders.Task.v1", "topic", "orders.created")
    bindQueue("public.orders.Order.v1", "topic", "orders.created")

    repeat(5) {
        publisher.publish(destination, OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()), aggregateType = "Task"), "orders.created")
        publisher.publish(destination, OutboxMessage(topic = "orders.created", payload = JsonObject(emptyMap()), aggregateType = "Order"), "orders.created")
    }

    // Ten publishes, two distinct exchange names. A declare per message is the defect this guards.
    assertEquals(2, declaredExchangeCount(publisher))
}
```

Write `messageCount(exchange: String): Int` and `declaredExchangeCount(publisher: RabbitPublisher): Int`
in the test class beside the existing `bindQueue`. Confirm the real signature of
`RabbitPublisher.publish` before you write these, and match it; the existing tests at line 73 show
its shape. For `declaredExchangeCount`, expose the per-channel declared-name set to the test the way
`isChannelOpen` is already exposed on `RabbitConsumer`, rather than counting through the broker.


- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :rabbitmq:test --tests '*Exchange*'`
Expected: FAIL. The first fails because the exchange is a fixed string.

- [ ] **Step 3: Resolve the address in the router**

In `MessageRouter.route`, change the signature to take the row rather than the topic and payload
alone, and resolve the address:

```kotlin
    fun route(row: OutboxMessage): RoutingResult? {
        val matchedRoute = compiledRoutes.firstOrNull { (_, regex) -> regex.matches(row.topic) }?.first
        return matchedRoute?.let {
            val destination = destinations[it.destination] ?: return null
            val context = RoutingKeyRenderer.RowContext(row.topic, row.key, row.aggregateType, row.payload)
            val resolvedAddress = resolveAddress(destination, context)
            ...
        }
    }
```

`resolveAddress` renders the destination's address template through the renderer. Add
`resolvedAddress` to `RoutingResult`.

- [ ] **Step 4: Fail the row on an empty address**

An empty rendered address must fail the row through the normal retry path. Do not publish, do not
guess a name, and do not fall back to a default exchange. Raise a publish failure whose message
names the destination and the template, so an operator can see which template produced nothing.

- [ ] **Step 5: Declare each exchange once**

`RabbitPublisher.openChannel` declares `dest.exchange` once per channel. With a per-row exchange
that becomes a declare per message. Add a per-channel set of already-declared exchange names, and
declare only on the first use of a name. Clear the set when the channel is discarded, because a new
channel must declare again.

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `./gradlew :rabbitmq:test --tests '*Exchange*'`
Expected: PASS, all three.

- [ ] **Step 7: Run the gates and commit**

Run: `./gradlew check` and `./gradlew ktlintCheck detekt`

```bash
git add core outbox-service rabbitmq
git commit -m "feat(F-091): render the RabbitMQ exchange from the outbox row"
```

---

## Task 4: A destination can read its exchange from a column (F-091)

**Files:**
- Modify: `core/src/main/kotlin/Destination.kt`
- Modify: `outbox-service/src/main/kotlin/MessageRouter.kt`
- Test: `outbox-service/src/test/kotlin/`

**Interfaces:**
- Consumes: `resolveAddress` from Task 3.
- Produces: `Destination.RabbitMQ.exchangeFrom: String?`. The permitted values are exactly
  `aggregate_type`, `topic` and `key`. Task 6 validates the name at startup.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `the column value wins over the template`() {
    val destination = Destination.RabbitMQ(
        name = "events",
        exchange = "public.orders.{{ aggregateType }}.v1",
        exchangeFrom = "topic"
    )
    val row = OutboxMessage(topic = "explicit.exchange", payload = JsonObject(emptyMap()), aggregateType = "Task")

    val result = router.route(row)

    assertEquals("explicit.exchange", result?.resolvedAddress)
}

@Test
fun `a null column value fails the row`() {
    // exchangeFrom = "key" against a row whose key is null renders nothing, so the row must fail
    // rather than publish to a guessed name.
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `./gradlew :outbox-service:test --tests '*Router*'`
Expected: FAIL to compile, because `exchangeFrom` does not exist.

- [ ] **Step 3: Add the field and the precedence**

Add `val exchangeFrom: String? = null` to `Destination.RabbitMQ`. In `resolveAddress`, when
`exchangeFrom` is present read that column from the row and use it verbatim, ignoring the template.
Support exactly `aggregate_type`, `topic` and `key`. A null column value yields an empty address,
which Task 3 already fails.

Record the reason in a comment: these three are routing fields the application sets deliberately,
and a wider set would let a broker name come from data that was never meant to be routing.

- [ ] **Step 4: Run the tests to verify that they pass, run the gates, and commit**

Run: `./gradlew :outbox-service:test --tests '*Router*'`, then `./gradlew check` and
`./gradlew ktlintCheck detekt`

```bash
git add core outbox-service
git commit -m "feat(F-091): let a destination read its exchange from a permitted column"
```

---

## Task 5: The Kafka topic and the NATS subject render the same way (F-091)

**Files:**
- Modify: `core/src/main/kotlin/Destination.kt` — the Kafka and NATS variants
- Modify: `kafka/src/main/kotlin/KafkaPublisher.kt`, `nats/src/main/kotlin/NatsPublisher.kt`
- Test: `kafka/src/test/kotlin/`, `nats/src/test/kotlin/`

**Interfaces:**
- Consumes: `resolvedAddress` from Task 3 and `exchangeFrom` from Task 4.
- Produces: `Destination.Kafka.topicFrom` and `Destination.Nats.subjectFrom`, with the same
  permitted set.

- [ ] **Step 1: Write the failing tests**

One test per broker, in the shape of Task 3's first test: two aggregate types through one
destination reach two topics or two subjects. One test per broker for the empty-address failure.

- [ ] **Step 2: Run them, confirm they fail, then render the address**

Run: `./gradlew :kafka:test --tests '*Topic*' :nats:test --tests '*Subject*'`

Make the Kafka `topic` and the NATS subject render through the same `resolveAddress`, and add
`topicFrom` and `subjectFrom` with the same three permitted columns. The publishers read
`resolvedAddress`.

- [ ] **Step 3: Run the tests, run the gates, and commit**

```bash
git add core kafka nats outbox-service
git commit -m "feat(F-091): render the Kafka topic and the NATS subject from the outbox row"
```

---

## Task 6: A bad template or column fails the startup (F-092)

**Files:**
- Modify: `outbox-service/src/main/kotlin/StartupValidator.kt`
- Test: `outbox-service/src/test/kotlin/` and `app/src/test/kotlin/`

**Interfaces:**
- Consumes: every address template and `*From` field from Tasks 3, 4 and 5.
- Produces: `StartupValidator.validateAddressTemplates(config: QueueBoxConfig)`, called from the
  same place `validateTransforms` is called.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `an unknown template field fails the startup and names the field`() {
    val config = configWith(exchange = "public.{{ nosuchfield }}.v1", destinationName = "events")

    val error = assertFailsWith<InvalidDestinationException> {
        StartupValidator.validateAddressTemplates(config)
    }

    assertTrue(error.message!!.contains("nosuchfield"))
    assertTrue(error.message!!.contains("events"))
}

@Test
fun `an exchangeFrom outside the permitted set fails the startup`() {
    val config = configWith(exchangeFrom = "payload", destinationName = "events")

    val error = assertFailsWith<InvalidDestinationException> {
        StartupValidator.validateAddressTemplates(config)
    }

    assertTrue(error.message!!.contains("payload"))
    assertTrue(error.message!!.contains("aggregate_type"))
}

@Test
fun `a valid template and a valid column start cleanly`() {
    val config = configWith(exchange = "public.{{ aggregateType }}.v1", exchangeFrom = null)

    StartupValidator.validateAddressTemplates(config)
}
```

- [ ] **Step 2: Run them, confirm they fail, then implement**

Run: `./gradlew :outbox-service:test --tests '*StartupValidator*'`

The permitted template fields are exactly `topic`, `key`, `aggregateType`, and any field beginning
`payload.` or `data.`. The permitted `*From` columns are exactly `aggregate_type`, `topic` and
`key`. The error must name the offending value AND the destination, because an operator with twenty
destinations needs to know which one.

- [ ] **Step 3: Prove it fires in the real startup path**

A unit test on the validator is not enough. Add an application test that starts with the template
`{{ nosuchfield }}` and asserts the startup fails. If `validateAddressTemplates` is never called
from the startup, this test fails and tells you so.

- [ ] **Step 4: Run the gates and commit**

```bash
git add outbox-service app
git commit -m "feat(F-092): fail the startup on an unknown template field or column"
```

---

## Task 7: The documented insert shows headers and the aggregate type (F-093)

**Files:**
- Modify: `docs/integration.md`
- Test: `app/src/test/kotlin/docs/IntegrationDocSqlTest.kt`

**Interfaces:**
- Consumes: the `aggregate_type` column from Task 1.
- Produces: nothing that later work consumes. This is the last task of the phase.

- [ ] **Step 1: Read the existing harness**

`app/src/test/kotlin/docs/IntegrationDocSqlTest.kt` already executes the SQL samples in
`docs/integration.md`. Read it before you write a sample, so the new samples are picked up by the
harness rather than skipped in silence. Confirm how it selects a block, and match that shape.

- [ ] **Step 2: Write the failing test**

Assert that the document contains an insert sample per supported database, that each one populates
`headers` AND `aggregate_type`, and that each executes. The point of the finding is that the
adopter never found the `headers` column, so an assertion that merely runs the SQL is not enough:
assert that the sample sets both columns.

- [ ] **Step 3: Write the samples**

One insert per database, inside the application transaction, populating `headers` and
`aggregate_type`. State in one sentence above each that the insert must happen in the SAME
transaction as the business write, because that is the whole point of the outbox pattern.

- [ ] **Step 4: Add the Entity Framework Core mapping**

A .NET adopter maps an entity, not a table. Show the mapping for `headers` and for
`aggregate_type`, because the reporting adopter's entity mapped neither. This is the defect that
sent them to a transform.

- [ ] **Step 5: Run the gates and commit**

Run: `./gradlew check` and `./gradlew ktlintCheck detekt`

```bash
git add docs app
git commit -m "docs(F-093): document the outbox insert with headers and the aggregate type"
```

---

## Phase exit

- [ ] `./gradlew check` passes.
- [ ] `./gradlew ktlintCheck detekt` passes.
- [ ] The outbox table carries `aggregate_type`, and a row without one still publishes.
- [ ] Two aggregate types reach two exchanges through ONE destination, and the same holds for a
      Kafka topic and a NATS subject.
- [ ] An empty rendered address fails the row and publishes nothing.
- [ ] A template that names an unknown field fails the startup, and the error names the field and
      the destination.
- [ ] Every code sample in `docs/integration.md` runs under the document test harness.
- [ ] `docs/build/STATUS.md` records the phase and names the commits.

## Carried from Phase 7

- [ ] Restore the sentence in `docs/delivery-semantics.md` stating that QueueBox preserves no order
      between two different aggregates, marked as stated but not proven. It was removed when a test
      name was corrected to match what its test proves.
- [ ] Add a unit test for `clients/typescript/src/connections.ts`, which wraps its cleanup rollback
      in `.catch(() => undefined)`. That line keeps the lock-failure path quiet and nothing asserts
      it.
