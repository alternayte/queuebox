# Phase 10 — Client Packages and Words Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop every .NET consumer from writing the same registration and the same transaction
plumbing, and let a source name its own inbox attribute headers.

**Architecture:** A second NuGet package, `QueueBox.Inbox.DependencyInjection`, carries the
`AddQueueBoxInbox` extension and an Entity Framework Core helper. The core package keeps its single
dependency, so a consumer with no container pays nothing. The two packages ship on one tag at one
version, so they can never drift. On the server, a source gains an `attributeHeaders` block that the
three inbox consumers read before their existing fallback chain.

**Tech Stack:** C# 12 on net8.0, xUnit, Entity Framework Core. Kotlin, Gradle, Testcontainers,
JUnit 5. RabbitMQ, Kafka, NATS.

**Spec:** `docs/superpowers/specs/2026-09-08-adoption-work-order-design.md`, findings F-098 to
F-100.

## Scope note

This plan covers two independent subsystems: the .NET client packages (Tasks 1 to 3) and the Kotlin
inbox consumers (Task 4). They share no code and can be reviewed separately. They stay in one plan
because the Kotlin side is a single task, and a one-task plan carries more ceremony than it earns.

## Global Constraints

- THE IMPLEMENTER DOES NOT RUN `./gradlew check`. The controller runs the full gate. Narrow every
  Gradle run to a SINGLE TEST CLASS with `--tests`, never a whole module. Ten agents across the
  previous phases stalled by starting a long command and waiting on it. Run everything in the
  FOREGROUND with an explicit timeout; if a command auto-backgrounds, poll its output file to
  completion.
- The .NET suites are fast enough to run whole: `dotnet test` on a named project is fine.
- THE CORE PACKAGE KEEPS ITS SINGLE DEPENDENCY. `clients/csharp/src/QueueBox.Inbox` must depend on
  `Microsoft.Extensions.Logging.Abstractions` and nothing else, apart from the existing
  `Microsoft.NET.ILLink.Tasks` which is `PrivateAssets="all"`. `dotnet list package` is the proof.
  A consumer with no container pays nothing.
- THE TWO PACKAGES SHIP IN LOCKSTEP. One tag, `csharp-vX.Y.Z`, publishes both at the same version,
  and the new package depends on the core package at EXACTLY that version.
- ANY NEW CONFIGURATION FIELD NEEDS A REACHABILITY TEST, from YAML text through to the object that
  consumes it, asserting a NON-DEFAULT value. Phase 8 shipped a feature nobody could switch on
  because every test built domain objects directly. This binds Task 4's `attributeHeaders`.
- For every test, name the edit to the production code that would make it fail. If you cannot name
  one, the test proves nothing. Every phase of this programme has produced tests that could not
  fail, including a security guard and a defensive check that was inert.
- Write the test first. Run it. Confirm it fails FOR THE RIGHT REASON. Then implement.
- Prose follows ASD-STE100: no contraction, no `-ing` form outside a technical name, active voice,
  and `must` or `can` rather than `should` or `may`. Prose only, never code or identifiers.
- Reference the finding ID in every commit message, for example `F-098`.
- Never run `git revert`, `git reset --hard`, `git checkout -- .`, `git restore`, or `git clean`.

---

## File Structure

**F-098, the registration:**
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/QueueBox.Inbox.DependencyInjection.csproj`
- Create: `.../ServiceCollectionExtensions.cs` — `AddQueueBoxInbox`
- Create: `.../InboxWorkerHostedService.cs` — one hosted service per named worker
- Create: `clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests/`
- Modify: `clients/csharp/QueueBox.Inbox.slnx` — add both projects

**F-099, the Entity Framework Core helper:**
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/InboxDbContextFactory.cs`
- Modify: `clients/csharp/README.md` — the one correct usage, and what a second transaction costs

**The release:**
- Modify: `.github/workflows/release-clients.yml`

**F-100, the header names:**
- Modify: `config/src/main/kotlin/QueueBoxConfig.kt` — the `attributeHeaders` block
- Modify: `app/src/main/kotlin/App.kt` — carry it to all three consumer configurations
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt:359`,
  `kafka/src/main/kotlin/KafkaInboxConsumer.kt:331`,
  `nats/src/main/kotlin/NatsInboxConsumer.kt:296`
- Modify: `docs/configuration.md`

---

## Task 1: The dependency injection package registers named workers (F-098)

**Files:**
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/QueueBox.Inbox.DependencyInjection.csproj`
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/ServiceCollectionExtensions.cs`
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/InboxWorkerHostedService.cs`
- Create: `clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests/`
- Modify: `clients/csharp/QueueBox.Inbox.slnx`

**Interfaces:**
- Consumes: `InboxWorker(IInboxConnectionSource connections, InboxOptions options, ILogger<InboxWorker>? logger = null, TimeProvider? timeProvider = null)` and
  `Task InboxWorker.RunAsync(InboxHandler handler, CancellationToken cancellationToken = default)`,
  both in the core package. `InboxHandler` is
  `delegate Task (InboxMessage message, DbTransaction transaction, CancellationToken cancellationToken)`.
- Produces: `IServiceCollection AddQueueBoxInbox(this IServiceCollection services, string name, Action<InboxOptions> configure, InboxHandler handler)`.
  Task 2 adds an overload that resolves the handler from the container, and Task 3 packs this project.

- [ ] **Step 1: Read the core package first**

Read `clients/csharp/src/QueueBox.Inbox/InboxWorker.cs`, `InboxOptions.cs` and
`IInboxConnectionSource.cs`. Confirm the exact constructor and `RunAsync` signature before you
write anything: an earlier phase's brief named two methods that did not exist, and the implementer
that checked was right.

- [ ] **Step 2: Write the failing tests**

Create `clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests/ServiceCollectionExtensionsTests.cs`.

```csharp
[Fact]
public void Two_named_workers_both_run()
{
    var services = new ServiceCollection();
    services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());

    services.AddQueueBoxInbox("orders", o => o.Source = "orders", (m, t, c) => Task.CompletedTask);
    services.AddQueueBoxInbox("payments", o => o.Source = "payments", (m, t, c) => Task.CompletedTask);

    var hosted = services.BuildServiceProvider().GetServices<IHostedService>().ToList();

    // Two registrations must give two hosted services. The AddHostedService trap is that a second
    // registration of the same implementation type is dropped, and one worker disappears in
    // silence.
    Assert.Equal(2, hosted.Count);
}

[Fact]
public void A_duplicate_name_throws()
{
    var services = new ServiceCollection();
    services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());
    services.AddQueueBoxInbox("orders", o => o.Source = "orders", (m, t, c) => Task.CompletedTask);

    var error = Assert.Throws<InvalidOperationException>(
        () => services.AddQueueBoxInbox("orders", o => o.Source = "other", (m, t, c) => Task.CompletedTask));

    // The message must name the duplicate, because an operator with twenty workers needs to know
    // which one.
    Assert.Contains("orders", error.Message);
}

[Fact]
public void Each_worker_keeps_its_own_options()
{
    var services = new ServiceCollection();
    services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());
    services.AddQueueBoxInbox("orders", o => { o.Source = "orders"; o.BatchSize = 3; }, (m, t, c) => Task.CompletedTask);
    services.AddQueueBoxInbox("payments", o => { o.Source = "payments"; o.BatchSize = 7; }, (m, t, c) => Task.CompletedTask);

    var hosted = services.BuildServiceProvider().GetServices<IHostedService>()
        .Cast<InboxWorkerHostedService>().ToList();

    // A shared options instance is the other silent failure: both workers would poll one source.
    Assert.Equal(new[] { "orders", "payments" }, hosted.Select(h => h.Options.Source).OrderBy(s => s));
    Assert.Equal(new[] { 3, 7 }, hosted.Select(h => h.Options.BatchSize).OrderBy(n => n));
}
```

Write `FakeConnectionSource` in the test project. It implements `IInboxConnectionSource` and its
`OpenAsync` throws, because these tests never start a worker; they assert the registration only.

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `dotnet test clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests`
Expected: FAIL to build, because the project and `AddQueueBoxInbox` do not exist.

- [ ] **Step 4: Create the project**

`QueueBox.Inbox.DependencyInjection.csproj` targets `net8.0` through the existing
`Directory.Build.props`. It references the core project, and adds
`Microsoft.Extensions.Hosting.Abstractions` and `Microsoft.Extensions.DependencyInjection.Abstractions`.
Copy the package metadata shape from `QueueBox.Inbox.csproj`: the same authors, licence, repository
and README conventions. Add both the project and its test project to `QueueBox.Inbox.slnx`.

- [ ] **Step 5: Write the hosted service**

```csharp
/// Runs one named worker for the whole life of the host. One instance per registration, so two
/// registrations give two workers rather than one.
public sealed class InboxWorkerHostedService : BackgroundService
{
    internal InboxOptions Options { get; }
    // Build the InboxWorker from the injected IInboxConnectionSource, the options of THIS
    // registration, and the logger. Run the handler in ExecuteAsync until the token cancels.
}
```

Expose `Options` as `internal` and make the test project a friend with `InternalsVisibleTo`, so the
third test can assert per-worker options without widening the public surface.

- [ ] **Step 6: Write the extension**

`AddQueueBoxInbox` builds a fresh `InboxOptions`, applies `configure`, calls `Validate()`, and
registers ONE `InboxWorkerHostedService` carrying that instance. Register with
`services.AddSingleton<IHostedService>(...)` rather than `AddHostedService<T>()`, because the
generic form registers by implementation type and a second registration of the same type is
dropped. That silent drop is the trap this finding exists to remove; say so in a comment.

Track the registered names in the service collection and throw `InvalidOperationException` naming
the duplicate when one repeats.

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `dotnet test clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests`
Expected: PASS, all three.

- [ ] **Step 8: Prove the core package stayed clean**

Run: `dotnet list clients/csharp/src/QueueBox.Inbox package`
Expected: `Microsoft.Extensions.Logging.Abstractions` only, apart from the private ILLink entry.
Paste the output into your report. If the new project's reference leaked a dependency into the core
package, this is where it shows.

- [ ] **Step 9: Commit**

```bash
git add clients/csharp
git commit -m "feat(F-098): add QueueBox.Inbox.DependencyInjection with AddQueueBoxInbox"
```

---

## Task 2: The Entity Framework Core helper writes in the message transaction (F-099)

**Files:**
- Create: `clients/csharp/src/QueueBox.Inbox.DependencyInjection/InboxDbContextFactory.cs`
- Modify: `clients/csharp/README.md`
- Test: `clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests/`

**Interfaces:**
- Consumes: `AddQueueBoxInbox` from Task 1, and the `InboxHandler` delegate's `DbTransaction`.
- Produces: `TContext InboxDbContextFactory.CreateOn<TContext>(DbTransaction transaction, Func<DbContextOptions<TContext>, TContext> build) where TContext : DbContext`.

- [ ] **Step 1: Write the failing test**

This test is the whole point of the finding, so it must prove the rollback rather than the happy
path.

```csharp
[Fact]
public async Task A_write_through_the_helper_rolls_back_when_the_handler_throws()
{
    await using var harness = await PostgresHarness.CreateAsync();
    await harness.SeedPendingAsync(source: "orders", count: 1);

    var handler = new InboxHandler(async (message, transaction, token) =>
    {
        await using var context = InboxDbContextFactory.CreateOn<OrderContext>(
            transaction, options => new OrderContext(options));
        context.Orders.Add(new Order { Id = message.Id });
        await context.SaveChangesAsync(token);

        throw new InvalidOperationException("the handler failed after it wrote");
    });

    await harness.RunWorkerOnceAsync(handler);

    // The application write and the completion share one transaction. A handler that throws must
    // leave neither behind.
    Assert.Equal(0, await harness.CountOrdersAsync());
    Assert.Equal("pending", await harness.InboxStateAsync());
}

[Fact]
public async Task A_write_through_the_helper_commits_with_the_completion()
{
    await using var harness = await PostgresHarness.CreateAsync();
    await harness.SeedPendingAsync(source: "orders", count: 1);

    var handler = new InboxHandler(async (message, transaction, token) =>
    {
        await using var context = InboxDbContextFactory.CreateOn<OrderContext>(
            transaction, options => new OrderContext(options));
        context.Orders.Add(new Order { Id = message.Id });
        await context.SaveChangesAsync(token);
    });

    await harness.RunWorkerOnceAsync(handler);

    Assert.Equal(1, await harness.CountOrdersAsync());
    Assert.Equal("processed", await harness.InboxStateAsync());
}
```

`PostgresHarness`, `OrderContext` and `Order` go in the test project. Reuse the container fixture
the existing integration tests already use rather than starting a second one; read
`clients/csharp/tests/QueueBox.Inbox.IntegrationTests` first and follow it.

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `dotnet test clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests`
Expected: FAIL to build, because `InboxDbContextFactory` does not exist.

- [ ] **Step 3: Write the helper**

```csharp
/// Builds a context that writes inside the transaction of the message.
///
/// Entity Framework Core opens its own connection by default, and a write on a second connection
/// commits on its own. The inbox guarantee is that the application write and the completion
/// commit together, so the context must take the connection AND the transaction of the message.
public static TContext CreateOn<TContext>(
    DbTransaction transaction,
    Func<DbContextOptions<TContext>, TContext> build)
    where TContext : DbContext
```

Build the options over `transaction.Connection`, construct the context through `build`, then call
`context.Database.UseTransaction(transaction)`. Throw a clear error when `transaction.Connection`
is null, naming what went wrong, rather than letting a null reference surface later.

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `dotnet test clients/csharp/tests/QueueBox.Inbox.DependencyInjection.Tests`
Expected: PASS.

- [ ] **Step 5: Document the one correct usage**

Add a section to `clients/csharp/README.md` showing the helper inside a handler. Then state plainly
what happens when a handler opens its OWN transaction or its own context instead: that write commits
on its own connection, so a later failure leaves the application row written and the inbox row
unprocessed, and the message is delivered again. That is the failure this helper exists to prevent,
and a reader who does not see it stated will reach for a plain `DbContext`.

- [ ] **Step 6: Commit**

```bash
git add clients/csharp
git commit -m "feat(F-099): build an Entity Framework Core context on the message transaction"
```

---

## Task 3: Both packages ship on one tag at one version

**Files:**
- Modify: `.github/workflows/release-clients.yml`

**Interfaces:**
- Consumes: the project created in Task 1.
- Produces: nothing that later work consumes.

- [ ] **Step 1: Read the workflow first**

Read `.github/workflows/release-clients.yml`. The C# job restores, builds, tests, packs
`clients/csharp/src/QueueBox.Inbox`, then pushes to nuget.org through trusted publishing. Note how
the version reaches `dotnet pack`, and how the artifact is attached to the GitHub release.

- [ ] **Step 2: Pack and push both**

Extend the job to pack `clients/csharp/src/QueueBox.Inbox.DependencyInjection` with the SAME version
from the same tag, and push both packages. The new package must depend on the core package at
EXACTLY that version, so the two can never drift; set that in the csproj through the same version
property the pack step supplies.

- [ ] **Step 3: Prove the dependency pins exactly**

Add a step that inspects the produced `.nuspec` and fails when the core dependency is not an exact
version match for the tag. A lockstep claim nobody checks is a lockstep claim that breaks quietly.
State the command in your report.

- [ ] **Step 4: Verify without publishing**

You cannot publish from here, and you must not try. Run the pack locally instead:
`dotnet pack clients/csharp/src/QueueBox.Inbox.DependencyInjection -c Release -p:Version=0.2.0-test -o /tmp/qbpack`
then unzip the `.nupkg` and confirm the `.nuspec` names `QueueBox.Inbox` at exactly `0.2.0-test`.
Paste that dependency line into your report.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release-clients.yml clients/csharp
git commit -m "chore(F-098): publish QueueBox.Inbox.DependencyInjection in lockstep"
```

---

## Task 4: A source names its own inbox attribute headers (F-100)

**Files:**
- Modify: `config/src/main/kotlin/QueueBoxConfig.kt`
- Modify: `app/src/main/kotlin/App.kt`
- Modify: `rabbitmq/src/main/kotlin/RabbitConsumer.kt` around line 359
- Modify: `kafka/src/main/kotlin/KafkaInboxConsumer.kt` around line 331
- Modify: `nats/src/main/kotlin/NatsInboxConsumer.kt` around line 296
- Modify: `docs/configuration.md`

**Interfaces:**
- Consumes: nothing from Tasks 1 to 3. This task is independent of the .NET work.
- Produces: an `attributeHeaders` block on the source configuration, carrying
  `idempotencyKey`, `aggregateId` and `eventType`, each defaulting to the current header name.

- [ ] **Step 1: Read all three consumers first**

Read the idempotency-key, aggregate-id and event-type extraction in each of the three files. They
share a fallback chain: the header, then a JSONPath into the payload, then a message property, then
a digest. Your setting sits ABOVE that chain and replaces only which header NAME is read. The rest
of the chain must not change.

- [ ] **Step 2: Write the failing tests**

One per broker, in the existing integration test class for each.

```kotlin
@Test
fun `a source reads the attribute names that the configuration gives it`() = runBlocking {
    val source = "debezium-source"
    startConsumer(
        source,
        attributeHeaders = AttributeHeaders(
            idempotencyKey = "id",
            aggregateId = "aggregateId",
            eventType = "eventType"
        )
    )

    // A flat payload with Debezium's header names, and NO field in the body that the fallback
    // chain could read. Only the configured header names can satisfy this.
    publish(
        source,
        body = """{"orderTotal":42}""".toByteArray(),
        headers = mapOf("id" to "evt-1", "aggregateId" to "order-7", "eventType" to "OrderPlaced")
    )

    val row = awaitSingleInboxRow(source)
    assertEquals("evt-1", row.idempotencyKey)
    assertEquals("order-7", row.aggregateId)
    assertEquals("OrderPlaced", row.eventType)
}

@Test
fun `the default names still work when the configuration sets nothing`() = runBlocking {
    val source = "default-source"
    startConsumer(source)

    publish(
        source,
        body = """{"orderTotal":42}""".toByteArray(),
        headers = mapOf(
            "x-idempotency-key" to "evt-2",
            "x-aggregate-id" to "order-8",
            "x-event-type" to "OrderPaid"
        )
    )

    val row = awaitSingleInboxRow(source)
    assertEquals("evt-2", row.idempotencyKey)
    assertEquals("order-8", row.aggregateId)
    assertEquals("OrderPaid", row.eventType)
}
```

Adapt the helper names to each test class rather than inventing a new harness. The second test is
not optional: it is what proves no existing deployment changes.

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `./gradlew :rabbitmq:test --tests '*RabbitConsumerIntegrationTest*'` with a timeout you set.
Expected: FAIL to compile, because `AttributeHeaders` does not exist.

- [ ] **Step 4: Add the configuration block**

```kotlin
/**
 * The header names that carry the three inbox attributes. F-100.
 *
 * The defaults are the names QueueBox has always read. A Debezium producer sends `id`,
 * `eventType` and `aggregateId` instead, and before this setting a flat payload with those
 * headers needed a code change.
 */
data class AttributeHeaders(
    val idempotencyKey: String = "x-idempotency-key",
    val aggregateId: String = "x-aggregate-id",
    val eventType: String = "x-event-type"
)
```

Add `val attributeHeaders: AttributeHeaders = AttributeHeaders()` to the source configuration, and
carry it through `App.kt` to all three consumer configurations.

- [ ] **Step 5: Read the configured name in each consumer**

In each of the three consumers, replace the hardcoded header literal with the configured name. Keep
the rest of the fallback chain exactly as it is. Do NOT change the order of the chain, and do not
remove the digest fallback.

- [ ] **Step 6: Run the three test classes**

Run each narrowed to its own class, in the foreground with a timeout you set:
`./gradlew :rabbitmq:test --tests '*RabbitConsumerIntegrationTest*'`, then the Kafka and NATS
equivalents.
Expected: PASS.

- [ ] **Step 7: Write the reachability test and prove it bites**

A new configuration field needs a test from YAML text through to the object that consumes it,
asserting a NON-DEFAULT value. Follow `app/src/test/kotlin/SourceFromYamlTest.kt`. Then delete the
line that carries `attributeHeaders` into one consumer configuration, confirm the test FAILS,
restore it, confirm it PASSES, and confirm `git diff` is clean. Report both results.

- [ ] **Step 8: Document it**

Add `attributeHeaders` to `docs/configuration.md` beside the other source settings, with the three
defaults and one sentence naming the case it serves: a producer that sends `id`, `eventType` and
`aggregateId` rather than the `x-` names.

- [ ] **Step 9: Commit**

```bash
git add config app rabbitmq kafka nats docs
git commit -m "feat(F-100): let a source name its own inbox attribute headers"
```

---

## Phase exit

- [ ] `./gradlew check` passes, AND `./gradlew ktlintCheck detekt` passes. The controller runs both;
      `check` alone does NOT include ktlint in this project, which cost a red pull request in the
      previous phase.
- [ ] `dotnet test` passes for every C# project.
- [ ] `dotnet list clients/csharp/src/QueueBox.Inbox package` shows the single dependency.
- [ ] Two named workers both run, and a duplicate name throws.
- [ ] A write through the helper rolls back with the message when the handler throws.
- [ ] The produced `.nuspec` pins the core package at the exact tag version.
- [ ] A source reads configured attribute names, and the defaults still work.
- [ ] `docs/build/STATUS.md` records the phase and names the commits.

## Carried from earlier phases

- [ ] Restore the sentence in `docs/delivery-semantics.md` stating that QueueBox preserves no order
      between two different aggregates, marked as stated but not proven.
- [ ] Add a unit test for `clients/typescript/src/connections.ts`, whose cleanup rollback is wrapped
      in `.catch(() => undefined)` with nothing asserting it.
- [ ] Scope `distinctTopics()` to `state IN ('sent','dead')`, which bounds the scan and matches what
      a replay can move.
- [ ] Raise the SQL Server local-timestamp storage defect as its own finding. `created_at` is
      `DATETIME2` and the driver converts through the JVM default calendar, so a row stores the host
      local wall clock rather than a UTC instant.
- [ ] Triage the load-sensitive test family as one finding rather than case by case.
- [ ] Remove the `.trivyignore` entries for CVE-2026-76956 and CVE-2026-76957 once
      `eclipse-temurin:21-jre-alpine` publishes a rebuild carrying libexpat 2.8.4-r0. Recheck
      2026-10-09.
