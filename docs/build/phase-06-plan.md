# Phase 6 — Documentation and polish: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close finding F-072 to F-085. Give QueueBox a documentation set that an adopter can use,
and correct the four places where the shipped code contradicts the document.

**Architecture:** Most of the phase is documentation. Four findings need product code. F-076 adds an
external configuration file path. F-077 removes or uses the dead state machine. F-078 changes the
inbox accept response to 202. F-080 replaces a raw `ClassNotFoundException` with a named error. Each
code change lands with its test before the document that describes it.

**Tech Stack:** Kotlin, Ktor, Hoplite, Gradle, Testcontainers, Docker Compose, GitHub Actions,
Mermaid.

**Spec:** `hardening-doc.md`, section 9.

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed. Decision 1: QueueBox forwards inbox rows itself. Decision 5:
  preserve no Task Master planning content. Write every document from the shipped code.
- Section 2A of `hardening-doc.md` line 96 holds the exact positioning statement. Use it verbatim
  when the README is rewritten.
- Write the test first where a test is possible. A document has no unit test, so the test is the
  one that executes the sample, or the one that asserts the document and the code agree.
- `./gradlew check` must pass after every finding. `./gradlew ktlintCheck detekt` must pass too.
- Every English sentence follows ASD-STE100 Simplified Technical English.

## Facts this plan is built on

Read these before Task 1. Each was measured in the tree at commit `76b901c`.

| Fact | Value |
|------|-------|
| `README.md` | 1142 lines, 19 top level sections |
| `docs/` already holds | `development/{building,migrations,releasing}.md`, `operations/{runbook,dead-letter,security}.md`, `build/` |
| `.env.example` | 12 AI provider keys. No QueueBox variable. |
| `docker-compose.yml` app service | sets `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_URL`. The application reads none of them. |
| `ConfigLoader.load` | `addResourceSource("/$path")` only. There is no file source. |
| `MessageState` | `Pending`, `Processing`, `Sent`, `Failed(error, attempt)`, `Dead`. `canTransitionTo` has no caller. |
| `InboxRoutes.kt` | returns 200 at line 121 for a new message and 200 at line 124 for a duplicate. It can also return 400, 413, 422, 500 and the auth codes. |
| `DatabaseProviderFactory` | uses `Class.forName` at two sites and lets `ClassNotFoundException` escape. |
| Metric names | 23 `queuebox_*` builders in `QueueBoxMetrics.kt`. `postgres/DatabaseFactory.kt` also registers HikariCP metrics through `MicrometerMetricsTrackerFactory`. |

## File structure

| File | Responsibility |
|------|----------------|
| `README.md` | What QueueBox is, the guarantees, a 60 second quick start, and links. Under 200 lines. |
| `docs/getting-started.md` | The quick start in full, with Compose and with a manual run. |
| `docs/configuration.md` | The full configuration reference, moved out of the README. |
| `docs/integration.md` | The outbox insert contract. The product surface an adopter writes against. |
| `docs/architecture.md` | The module graph, the message lifecycle, and the state diagram. |
| `docs/transforms.md` | The transform reference, moved out of the README. |
| `docs/authentication.md` | The destination and source authentication reference, moved out of the README. |
| `docs/operations/metrics.md` | Every exposed metric, including the pool and JVM metrics. |
| `docs/adr/0001-reflection-for-database-providers.md` | Why the repository layer uses reflection. |
| `docs/adr/0002-inbox-accept-returns-202.md` | Why the inbox accept response changed. |
| `examples/webhook-receiver/`, `examples/http-fanout/`, `examples/rabbitmq-bridge/` | One runnable example each: `queuebox.yml`, `docker-compose.yml`, `README.md`, `smoke-test.sh`. |
| `config/src/main/kotlin/ConfigLoader.kt` | Gains an external file source. |
| `core/src/main/kotlin/repository/DatabaseProviderFactory.kt` | Gains a named error. |
| `inbox-service/src/main/kotlin/InboxRoutes.kt` | Returns 202 for a new message. |
| `app/src/test/kotlin/docs/` | The tests that execute the documented samples. |

---

### Task 1: F-074 and F-073 — Compose and `.env.example` configure the application

This task comes first. Every later task documents a quick start, and today that quick start does
not configure anything. Fix the mechanism before the document that describes it.

**Files:**
- Modify: `docker-compose.yml`, `docker-compose.override.yml`
- Rewrite: `.env.example`
- Create: `examples/queuebox.yml` (the mounted example configuration)
- Create: `app/src/test/kotlin/docs/ComposeEnvExampleTest.kt`
- Modify: `.github/workflows/ci.yml` (a `compose` job)

**Interfaces:**
- Consumes: nothing.
- Produces: `.env.example` with the variable names that Task 2 and Task 5 document. The mounted
  path `/etc/queuebox/queuebox.yml`, which Task 3 makes the default external path.

- [ ] **Step 1: Write the failing test.** `ComposeEnvExampleTest` parses `docker-compose.yml` and
      `.env.example` as text. Assert three things. Every variable the app service sets starts with
      `QUEUEBOX_`. `.env.example` contains `QUEUEBOX_DATABASE_URL`, `QUEUEBOX_DATABASE_USERNAME`,
      `QUEUEBOX_DATABASE_PASSWORD` and `QUEUEBOX_SERVER_HTTP_PORT`. `.env.example` contains no
      variable that matches `.*_API_KEY`.
- [ ] **Step 2: Run it.** `./gradlew :app:test --tests '*ComposeEnvExampleTest*'`. Expect a failure
      that names `DB_URL` and `ANTHROPIC_API_KEY`.
- [ ] **Step 3: Rewrite `.env.example`.** Include only QueueBox variables, plus one commented
      example each for a destination, a route and a source, taken from the shipped
      `config/src/main/resources/queuebox.yml`.
- [ ] **Step 4: Correct both Compose files.** Use `QUEUEBOX_DATABASE_URL`,
      `QUEUEBOX_DATABASE_USERNAME` and `QUEUEBOX_DATABASE_PASSWORD`. Mount
      `./examples/queuebox.yml` at `/etc/queuebox/queuebox.yml`. Keep the health check.
- [ ] **Step 5: Run the test.** Expect a pass.
- [ ] **Step 6: Prove the DoD by hand.** Run
      `docker compose --env-file .env.example up -d`, wait for the health check, then
      `curl -X POST localhost:8080/inbox/<source>` with a JSON body, and assert a success response.
      Record the exact commands and the exact output.
- [ ] **Step 7: Add the CI job.** A `compose` job in `.github/workflows/ci.yml` that runs the same
      sequence and fails on a non-success response.
- [ ] **Step 8:** `./gradlew check`. Then commit.

---

### Task 2: F-076 — Configuration loads from an external file

**Files:**
- Modify: `config/src/main/kotlin/ConfigLoader.kt`
- Modify: `app/src/main/kotlin/App.kt` (the call site, if it names a path)
- Create: `config/src/test/kotlin/ExternalConfigFileTest.kt`

**Interfaces:**
- Consumes: the mount path from Task 1.
- Produces: `ConfigLoader.loadAuto(path)` reads, in order: the file named by
  `QUEUEBOX_CONFIG_FILE`; then `/etc/queuebox/queuebox.yml`; then the classpath resource. The
  first source that holds a key wins. Task 5 documents this precedence.

- [ ] **Step 1: Write the failing test.** `ExternalConfigFileTest` writes a temporary YAML file
      that sets a value which differs from the packaged resource. It sets `QUEUEBOX_CONFIG_FILE`
      to that path, calls `ConfigLoader.loadAuto()`, and asserts the external value wins. A second
      test asserts that with no external file present the packaged resource still loads. A third
      test asserts a `QUEUEBOX_*` environment variable still overrides the external file.
- [ ] **Step 2: Run it.** `./gradlew :config:test --tests '*ExternalConfigFileTest*'`. Expect the
      first test to fail, because the packaged value is returned.
- [ ] **Step 3: Implement.** Add an `addFileSource(file, optional = true)` for the resolved
      external path before `addResourceSource`, and keep `addPropertySource(createEnvSource())`
      last so an environment variable still wins. Hoplite applies the first source that holds a
      key, so the order in the builder is the precedence.
- [ ] **Step 4: Run the test.** Expect all three to pass.
- [ ] **Step 5:** `./gradlew check`. Then commit.

---

### Task 3: F-078 — The inbox accept response becomes 202

**Files:**
- Modify: `inbox-service/src/main/kotlin/InboxRoutes.kt:121`
- Modify: `inbox-service/src/test/kotlin/org/nxtspec/InboxRoutesTest.kt`
- Create: `docs/adr/0002-inbox-accept-returns-202.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the complete response code set that Task 6 puts in the README table:
  202 accepted, 200 duplicate, 400 invalid JSON or a rejected message, 401 and 403 from
  authentication, 413 payload too large, 422 transform failed, 500 storage failed.

- [ ] **Step 1: Write the failing test.** Extend `InboxRoutesTest` with a test that posts a new
      message and asserts `HttpStatusCode.Accepted`. Add a second test that posts the same
      idempotency key twice and asserts 202 then 200. Add a third test, `enumerates every response
      code`, that drives the route to each of the eight codes above and asserts the set.
- [ ] **Step 2: Run it.** `./gradlew :inbox-service:test --tests '*InboxRoutesTest*'`. Expect a
      failure reporting 200 where 202 is expected.
- [ ] **Step 3: Implement.** Change line 121 to `HttpStatusCode.Accepted`. Leave the duplicate
      branch at 200.
- [ ] **Step 4: Run the test.** Expect a pass. Then run `./gradlew :app:test` too, because the end
      to end tests assert the inbox response.
- [ ] **Step 5: Record the change.** Add a `### Breaking changes` heading under the first release
      in `CHANGELOG.md`. Write `docs/adr/0002-inbox-accept-returns-202.md` recording that section
      2A of `hardening-doc.md` made the decision, that there are no public consumers, and that no
      compatibility flag exists.
- [ ] **Step 6:** `./gradlew check`. Then commit.

---

### Task 4: F-077 and F-080 — The state set and the provider error

Two findings, one task. Both replace a documented claim with a tested fact, and both need the same
`core` module test cycle.

**Files:**
- Modify: `core/src/main/kotlin/MessageState.kt`
- Modify: `core/src/main/kotlin/repository/DatabaseProviderFactory.kt:87-99`
- Create: `core/src/test/kotlin/DocumentedStateSetTest.kt`
- Create: `core/src/test/kotlin/DatabaseProviderFactoryTest.kt`
- Create: `docs/architecture.md`
- Create: `docs/adr/0001-reflection-for-database-providers.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `MissingDatabaseProviderException(type: DatabaseType, module: String)` in
  `core/src/main/kotlin/repository/`. Its message must name the Gradle module the user must add,
  for example `postgres`.

- [ ] **Step 1: Write the failing state test.** `DocumentedStateSetTest` reads
      `docs/architecture.md`, extracts the state names from the fenced block marked
      `<!-- states:outbox -->` and `<!-- states:inbox -->`, and compares each to the set of state
      literals the repositories write. Get the code side from `MessageState` and from a grep of the
      repository sources for a quoted state literal. Assert set equality in both directions.
- [ ] **Step 2: Write the failing provider test.** `DatabaseProviderFactoryTest` calls
      `DatabaseProviderFactory.create` with a stub class loader that cannot see
      `org.nxtspec.PostgresRepositoryFactory`, and asserts a `MissingDatabaseProviderException`
      whose message contains `postgres`.
- [ ] **Step 3: Run both.** `./gradlew :core:test --tests '*DocumentedStateSetTest*' --tests
      '*DatabaseProviderFactoryTest*'`. Expect the first to fail on a missing file and the second
      to fail with a raw `ClassNotFoundException`.
- [ ] **Step 4: Decide the state machine.** `canTransitionTo` has no caller. Either call it from
      every repository state write, or delete it. Deleting is the smaller change and the finding
      allows it. Record the choice and its reason in `docs/architecture.md`.
- [ ] **Step 5: Implement the provider error.** Catch `ClassNotFoundException` at both sites and
      throw `MissingDatabaseProviderException`.
- [ ] **Step 6: Write `docs/architecture.md`.** It must hold a Mermaid module graph, a Mermaid
      lifecycle sequence diagram, a Mermaid state diagram for the outbox and for the inbox, and
      the two marked state blocks the test reads. Write
      `docs/adr/0001-reflection-for-database-providers.md` recording why the repository layer uses
      reflection and what the failure mode now is.
- [ ] **Step 7: Run both tests.** Expect a pass. Then `./gradlew check`. Then commit.

---

### Task 5: F-084 — The integration contract, with every statement executed

This is the largest documentation task, and it carries the highest value. The outbox insert is the
product surface.

**Files:**
- Create: `docs/integration.md`
- Create: `app/src/test/kotlin/docs/IntegrationDocSqlTest.kt`

**Interfaces:**
- Consumes: the schema in `postgres/src/main/resources/db/migration/` and
  `sqlserver/src/main/resources/db/migration/`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test.** `IntegrationDocSqlTest` parses `docs/integration.md`,
      extracts every fenced block tagged ```sql postgres``` and ```sql sqlserver```, starts the
      matching Testcontainer with the shipped migrations applied, and executes each statement. For
      the outbox insert blocks it then asserts the row is delivered end to end by the running
      relay. Tag the test `@Tag("integration")`.
- [ ] **Step 2: Run it.** `./gradlew :app:test --tests '*IntegrationDocSqlTest*'`. Expect a failure
      on the missing file.
- [ ] **Step 3: Write `docs/integration.md`.** The finding names five required parts. Write all
      five.
      1. The required and optional outbox columns, with defaults, read from the migration. State
         whether `headers` can be null.
      2. A worked `INSERT` for PostgreSQL and for SQL Server, inside an application transaction.
      3. One ORM example, or the explicit statement that only raw SQL is documented. Choose the
         explicit statement unless the repository already ships an ORM example.
      4. The rule that the insert must share the transaction of the business write, and why. That
         shared transaction is the whole point of the outbox pattern.
      5. The inbox reading side, and decision 1 of section 2A: QueueBox forwards inbox rows
         itself, so an application consumes at the destination, not from the inbox table.
- [ ] **Step 4: Run the test.** Expect a pass.
- [ ] **Step 5:** `./gradlew check`. Then commit.

---

### Task 6: F-072, F-085, F-082, F-077 documentation, F-078 documentation — the README rewrite

**Files:**
- Rewrite: `README.md`
- Create: `docs/getting-started.md`, `docs/configuration.md`, `docs/transforms.md`,
  `docs/authentication.md`
- Create: `app/src/test/kotlin/docs/ReadmeStructureTest.kt`

**Interfaces:**
- Consumes: every document Task 4 and Task 5 created. The response code set from Task 3.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test.** `ReadmeStructureTest` asserts four things. `README.md` is
      under 200 lines. Every relative Markdown link in `README.md` resolves to a file that exists.
      Every roadmap line contains either a version string that matches `v\d+\.\d+` or the exact
      text `Not planned`. The README holds a `## Guarantees` section.
- [ ] **Step 2: Run it.** `./gradlew :app:test --tests '*ReadmeStructureTest*'`. Expect a failure
      reporting 1142 lines.
- [ ] **Step 3: Move the reference sections.** `## Configuration` to `docs/configuration.md`.
      `## Transforms` to `docs/transforms.md`. `## Authentication` and `## Security` to
      `docs/authentication.md`. `## Quick Start` and the corrected manual setup to
      `docs/getting-started.md`. Move the text. Do not rewrite it in this step, except to correct
      a statement that Task 1 to Task 4 proved wrong.
- [ ] **Step 4: Write the new README.** What QueueBox is, using the verbatim positioning statement
      from `hardening-doc.md` line 96. A 60 second quick start. A `## Guarantees` section. A link
      list to every document. Nothing else.
- [ ] **Step 5: Write `## Guarantees`.** F-085 names five items. Write all five as short
      declarative sentences: at-least-once delivery and the `X-Message-Id` header; where ordering
      holds and that concurrency inside a batch removes it between messages; that a claimed message
      returns to `pending` after the claim timeout and can duplicate; that the inbox deduplicates
      on `(source, idempotency_key)` within the retention period, and that retention shorter than
      the source retry window reopens duplicates; what each of `fail`, `skip` and `dead` does.
      Name the test that proves each one, and confirm each named test exists and passes.
- [ ] **Step 6: Correct the roadmap.** Attach a target version to each item, or mark it
      `Not planned`. Remove every item that Phase 3 and Phase 4 implemented.
- [ ] **Step 7: Correct the schema section.** Document the state set that Task 4 proved, and
      `VARCHAR(50)`, not `VARCHAR(20)`. Document every inbox response code from Task 3.
- [ ] **Step 8: Run the test.** Expect a pass. Then `./gradlew check`. Then commit.

---

### Task 7: F-079 — The metrics document equals the scrape

**Files:**
- Create: `docs/operations/metrics.md`
- Create: `app/src/test/kotlin/docs/MetricsDocTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Write the failing test.** `MetricsDocTest` starts the application against a
      Testcontainer, scrapes `/metrics`, and reads the metric names from the table in
      `docs/operations/metrics.md`. Assert set equality after a documented prefix allowlist is
      applied. Put the allowlist in the document itself, in a block marked
      `<!-- metrics:allowlist -->`, so the document states which prefixes it does not enumerate.
      Remember that the Prometheus exporter strips the `_info` suffix, which Phase 4 recorded.
- [ ] **Step 2: Run it.** Expect a failure on the missing file.
- [ ] **Step 3: Write `docs/operations/metrics.md`.** Document the 23 `queuebox_*` metrics, the
      HikariCP pool metrics that `postgres/DatabaseFactory.kt` registers through
      `MicrometerMetricsTrackerFactory`, and the JVM metrics. Give each a type and a meaning.
- [ ] **Step 4: Run the test.** Expect a pass. Then `./gradlew check`. Then commit.

---

### Task 8: F-081 and F-075 — The examples, each with a smoke test

**Files:**
- Create: `examples/webhook-receiver/{queuebox.yml,docker-compose.yml,README.md,smoke-test.sh}`
- Create: `examples/http-fanout/{queuebox.yml,docker-compose.yml,README.md,smoke-test.sh}`
- Create: `examples/rabbitmq-bridge/{queuebox.yml,docker-compose.yml,README.md,smoke-test.sh}`
- Modify: `.github/workflows/ci.yml` (an `examples` job)
- Modify: `docs/getting-started.md` (the corrected manual setup)

**Interfaces:**
- Consumes: the `QUEUEBOX_*` variables from Task 1 and the external file path from Task 2.
- Produces: nothing.

- [ ] **Step 1: Write the smoke test of the first example.** `smoke-test.sh` must start the
      Compose stack, wait for the health check, drive one message through, assert the expected
      result, and exit non-zero on any failure. Write it before the configuration it tests.
- [ ] **Step 2: Run it.** Expect a failure, because no `queuebox.yml` exists yet.
- [ ] **Step 3: Write the first example.** A webhook receiver: an inbox source that accepts a
      webhook and forwards it to an HTTP destination.
- [ ] **Step 4: Run the smoke test.** Expect a pass.
- [ ] **Step 5: Repeat step 1 to step 4 for `http-fanout`.** One outbox topic that matches two HTTP
      destinations.
- [ ] **Step 6: Repeat step 1 to step 4 for `rabbitmq-bridge`.** One outbox topic that publishes to
      a RabbitMQ exchange.
- [ ] **Step 7: Correct the manual setup.** F-075: `docs/getting-started.md` must use the
      `QUEUEBOX_*` names and must include the schema step. Every step must be executed verbatim by
      the CI job, so write the steps as a script the job runs.
- [ ] **Step 8: Add the CI job.** An `examples` job that runs all three smoke tests and the manual
      setup script.
- [ ] **Step 9:** `./gradlew check`. Then commit.

---

### Task 9: F-083 — Badges, identity and support policy

This task runs last, because a badge points at a workflow that the earlier tasks change.

**Files:**
- Modify: `README.md`
- Create: `SUPPORT.md`

- [ ] **Step 1:** Add a build status badge, a coverage badge, a license badge and a latest release
      badge to `README.md`.
- [ ] **Step 2:** Add a `## Support` section, or `SUPPORT.md` linked from the README, stating the
      support policy and the maintenance status.
- [ ] **Step 3:** Check every badge URL resolves. A badge for a workflow that has never run cannot
      resolve. Record that as a deviation, exactly as Phase 5 recorded the same limit for F-059 and
      F-063. Do not claim a badge resolves without the evidence.
- [ ] **Step 4:** Add the repository description and the topics. That needs the GitHub settings,
      which only the maintainer can change. Write the exact description and topic list into
      `docs/build/STATUS.md` as an item for the maintainer.
- [ ] **Step 5:** `./gradlew check`. Then commit.

---

## Phase exit condition

`hardening-doc.md` section 3: the documents are restructured under `docs/`, and every code sample in
every document is executed by a test.

The evidence is these commands and their output.

```
./gradlew clean build check jacocoAggregatedReport
wc -l README.md                                    # under 200
./gradlew :app:test --tests '*docs.*'              # every document test
docker compose --env-file .env.example up -d       # F-073 and F-074
examples/*/smoke-test.sh                           # F-081
```

## Known deviations to expect

- A badge cannot resolve until the workflow runs on the default branch. Phase 5 already recorded
  this for F-059 and F-063.
- The repository description and the topics need GitHub settings that only the maintainer can
  change.
