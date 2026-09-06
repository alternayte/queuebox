# QueueBox Hardening — Build Status

Source of truth: `hardening-doc.md`. The document is immutable and authoritative.

## Phase table

| Phase | Title | Findings | Status |
|-------|-------|----------|--------|
| 1 | Truth in advertising | F-001 to F-012 | **complete** (commit `eeb1d00`) |
| 2 | Durability and correctness | F-013 to F-033 | **complete** (commits `93afae4`..`8960a00`) |
| 3 | Security hardening | F-034 to F-045 | **complete** (commits `fb1f01f`..`f3fc59d`) |
| 4 | Observability and operations | F-046 to F-057 | **complete** (commits `b261c3c`..`e86b6d2`) |
| 5 | Open source governance | F-058 to F-071 | **complete** (see Phase 5 below) |
| 6 | Documentation and polish | F-072 to F-085 | **complete** (see Phase 6 below) |

---

## Phase 1 — complete

Plan: `docs/build/phase-01-plan.md`. Commit: `eeb1d00`.

### Findings closed

| ID | Fix | Evidence |
|----|-----|----------|
| F-001 | The inbox claim is one statement against the base table. A transaction advisory lock serialises the claim across replicas. | `InboxRepositoryConcurrencyTest`, `SqlServerInboxRepositoryConcurrencyTest` |
| F-002 | `InboxRelay` moves an inbox row into the outbox table in one transaction. | `InboxRelayTest`, `E2EInboxRelayTest` |
| F-003 | `RabbitPublisher` is registered. `validatePublisherCoverage` fails at startup. | `PublisherRegistrationTest`, `E2EOutboxFlowTest` |
| F-004 | `PublishContext` carries the route routing key to the publisher. | `RabbitPublisherRoutingKeyTest`, `OutboxPollerTest` |
| F-005 | The README documents only the supported placeholder forms. | `RoutingKeyTemplateContractTest` |
| F-006 | `claimed_at` plus `reclaimStale` recover a crashed claim. | `ReclaimStaleTest`, `E2ECrashRecoveryTest`, `OutboxPollerTest` |
| F-007 | The inbox cleanup uses the inbox states. | `RetentionBatchingTest` |
| F-008 | The delete statements take a limit. | `RetentionBatchingTest` |
| F-009 | The outbox claim orders the rows and skips locked rows. | `OutboxRepositoryConcurrencyTest` |
| F-010 | The transform cache is a bounded LRU. | `TransformEngineCacheTest` |
| F-011 | The table names are validated, and every identifier is quoted. | `ConfigValidatorTest`, `CustomTableNameTest` |
| F-012 | `LICENSE` holds the MIT text, and the README links to it. | `head -3 LICENSE` |

### Exit condition evidence

Command: `./gradlew clean build check jacocoAggregatedReport`

```
BUILD SUCCESSFUL in 3m 39s
72 actionable tasks: 72 executed
```

Aggregate coverage from `build/reports/jacoco/aggregated/jacocoAggregatedReport.xml`:

```
BRANCH: 0.7271 (618/850)
LINE:   0.7632 (2053/2690)
```

Both values pass the current gates of 0.72 line and 0.65 branch. F-069 raises the
gates at the end of Phase 2.

### Decisions recorded during Phase 1

1. **One message per aggregate.** The claim takes one advisory lock per inbox table,
   so only one claim runs at a time against that table. The SQL then excludes an
   aggregate that has a committed row in state `processing`, and Kotlin keeps the
   oldest message per aggregate inside the batch. The committed-read exclusion alone
   is not enough, because a replica cannot see the uncommitted claim of another
   replica. The cost is that the claim does not run in parallel.
2. **SQL Server aggregate exclusion.** The exclusion runs as its own statement. Inside
   the claim statement the same subquery makes SQL Server return no rows to a second
   concurrent claimer.
3. **SQL Server lock owner.** `sp_getapplock` uses `Session`, because the driver runs
   the claim with `@@TRANCOUNT` 0. The release runs in a `finally` block, and the
   connection pool reset is the second safety net.
4. **`claimed_at` column mapping.** `claimedAt` was added to the outbox and inbox
   column mapping, so an adopter with a custom schema can still map every column.
5. **Inbox `dead` state.** F-002 requires the relay to fail a message to dead. The
   inbox therefore has a `dead` state, and the inbox retention cleans `processed` and
   `dead`.
6. **Source topic validation.** An HTTP source whose topic template uses
   `{{ eventType }}` must set `eventTypePath`. Without the check the relay would mark
   every message of that source as dead. `ConfigValidator` rejects the configuration
   at startup.
7. **Test containers.** The PostgreSQL, SQL Server and RabbitMQ containers and their
   data sources are JVM wide singletons. A per-class container stopped after the first
   test class and left later classes with a closed pool.

### Deviations from the Definition of Done

- **F-006.** The DoD asks for an end to end test that "cancels the poller between claim
  and publish". `E2ECrashRecoveryTest` claims the batch directly and then starts a new
  poller. The state under test is identical, a row in `processing` with no live owner,
  and the current poller has no hard cancel. A hard cancel belongs to F-050, the
  graceful shutdown finding in Phase 4.

### Known risks carried into Phase 2

- `RabbitPublisher` opens a channel, declares the exchange and waits for a confirm per
  message (F-020), and it sets the mandatory flag with no return listener (F-022).
  F-003 put that code on the live delivery path, so both findings are now live.
  Phase 2 owns them.
- The Exposed table definitions declare indexes that the migrations do not create. The
  V1 migrations already carry equivalent covering indexes, so production is not slower.
  F-030 in Phase 2 owns the migration runner and can align the two.

---

---

## Phase 2 — complete

Plan: `docs/build/phase-02-plan.md`. Commits `93afae4`, `67d4586`, `eddce74`, `40a90ec`,
`8960a00`.

### Findings closed

| ID | Fix | Evidence |
|----|-----|----------|
| F-013 | Each message runs inside try/catch. The retry strategy applies to that message, and the batch continues. | `OutboxPollerTest` |
| F-014 | The poller publishes up to `outbox.concurrency` messages at the same time. | `OutboxPollerTest` |
| F-015 | The pending count runs at most once per `outbox.pendingGaugeIntervalMs`. | `OutboxPollerTest` |
| F-016 | `last_error` column, two migrations, and `ErrorSanitizer`. | `ErrorSanitizerTest`, `E2EOutboxFlowTest` |
| F-017 | `markFailed` is gone. `scheduleRetry` is the only method that increments. | `OutboxRepositoryTest`, `E2EOutboxFlowTest` |
| F-018 | One actor coroutine owns the AMQP channel and performs every acknowledgement. | `RabbitConsumerConcurrencyTest` |
| F-019 | `stop` cancels the consumer tag, drains, then closes. | `RabbitConsumerConcurrencyTest` |
| F-020 | One cached confirm channel per destination behind a mutex. | `RabbitPublisherThroughputTest` |
| F-021 | The README states the measured throughput and names the test. | `RabbitPublisherThroughputTest` |
| F-022 | A return listener correlates an unroutable return to its confirm. | `RabbitPublisherIntegrationTest` |
| F-023 | The body cap runs before every read, on every route. | `InboxRoutesTest`, `BodySizeLimitTest` |
| F-024 | A per-source rate limit answers 429 with `Retry-After`. | `InboxRoutesTest` |
| F-025 | One JSON parse per message. | `IdempotencyExtractorTest` |
| F-026 | Precompiled, escaped, anchored patterns. | `MessageRouterTest` |
| F-027 | `stop()` returns in under two seconds with a one hour interval. | `RetentionServiceTest` |
| F-028 | `shutdown()` is bounded by `outbox.shutdownTimeoutMs`. | `OutboxPollerTest` |
| F-029 | Drain, then stop the server, then the services, then the resources. | `ShutdownSequenceTest`, `E2EShutdownTest` |
| F-030 | Flyway runs the bundled migrations at startup. | `PostgresMigratorTest`, `SqlServerMigratorTest`, `MigrationGuardTest` |
| F-031 | The two migration sets correspond one to one. `docs/development/migrations.md`. | file listing |
| F-032 | A column mapping using `order` and `user` works on both providers, for both tables. | `CustomTableNameTest`, `SqlServerCustomColumnTest` |
| F-033 | The README states the age column per table, and a test asserts it. | `RetentionSemanticsTest` |
| F-071 | Pulled forward from Phase 5. See below. | `grep -rn "Hello, " --include='*.kt' .` returns nothing |

### Exit condition evidence

Command: `./gradlew clean build check jacocoAggregatedReport --rerun-tasks`

```
BUILD SUCCESSFUL in 3m 44s
74 actionable tasks: 74 executed
```

Aggregate coverage:

```
BRANCH: 0.7719 (758/982)
LINE:   0.8782 (2531/2882)
```

The gates are now the values that decision 3 of section 2A requires: 80 percent aggregate line,
70 percent aggregate branch, 60 percent per module. `check` enforces all three.

### Decisions recorded during Phase 2

1. **F-071 pulled forward from Phase 5.** Decision 3 of section 2A requires the raised coverage
   gates at the end of Phase 2. The five template `Main.kt` files and the `utils` module held
   dead code that no test can reasonably cover, so the gate could not pass while they existed.
   The finding is a pure deletion, so pulling it forward carries no design risk.
2. **The coverage exclusion list.** `AppKt` and its lambdas are excluded, because only a started
   process runs `main`. `TESTING.md` names every exclusion and its reason, which is what F-070
   asks for. The same list now applies to the per-module reports and to the aggregated report.
3. **DoubleReceive removed.** F-023 asks to cap the `DoubleReceive` buffer. The route now reads
   the body once, under the cap, and reuses those bytes for the HMAC check and the JSON parse,
   so the plugin is not needed. Removing it deletes the second, uncapped buffer entirely.
4. **Migrations and a custom schema.** The bundled files name the default schema. QueueBox
   refuses to run them when the configuration renames a table or a column, and it tells the
   operator to set `database.migrate` to false. Templated migrations are not in the document.
5. **Migration idempotence is mandatory.** Flyway baselines an existing database at version 0
   and replays every file, so every file guards its own statements.

### Deviations from the Definition of Done

- **F-015.** The DoD suggests virtual time. The test uses wall-clock time with a 20 ms poll
  interval and a 5000 ms gauge interval, and asserts exactly one count call in 500 ms. The
  margin is large, so the check is stable.
- **F-030.** The plan named an `E2EMigrationTest` in the `app` module. The coverage lives in
  `PostgresMigratorTest` and `SqlServerMigratorTest` instead. Both start against an empty
  database with no init script, apply every migration, and then run the message path through the
  repositories. No test boots `main` itself, because `main` blocks on the HTTP server.

### Findings from the audit that were not acted on

- **C4.** A RabbitMQ destination serialises its publishes behind one channel mutex, so
  `outbox.concurrency` raises throughput only across destinations. That follows from the F-020
  cached channel and the F-021 synchronous confirm, which the document accepts. The README now
  states it.
- **C6.** The rate limiter uses one bucket per source rather than one per client. The F-024
  Definition of Done is met. A per-client key is a design change that the document does not ask
  for. Record it for Phase 3, which owns the security findings.

---

## Phase 3 — complete

Plan: `docs/build/phase-03-plan.md`. Commits `fb1f01f`, `e821bdf`, `f3fc59d`.

### Findings closed

| ID | Fix | Evidence |
|----|-----|----------|
| F-034 | The admin endpoint is disabled by default, needs authentication, and clamps the timeout and the payload. | `AdminGuardTest`, `AdminRoutesTest` |
| F-035 | An HMAC signature covers `timestamp + "." + body` when a timestamp header is configured. | `InboxAuthValidatorTest` |
| F-036 | The Authorization header is parsed into scheme and credentials, with a case-insensitive scheme. | `InboxAuthValidatorTest` |
| F-037 | `secureCompare` calls `MessageDigest.isEqual` over SHA-256 digests. | `InboxAuthValidatorTest` |
| F-038 | A `Secret` value class carries every credential. The enclosing classes mask the credential parts of a URL and of a static header. | `SecretTest`, `ConfigSecretTest` |
| F-039 | A failed publish reads at most `http.maxErrorBodyBytes` from the channel, then redacts. | `HttpPublisherTest` |
| F-040 | Every outbound URL is validated. No redirect is followed. The path cannot carry a dot segment. | `ConfigValidatorTest`, `HttpPublisherTest` |
| F-041 | `docs/operations/security.md` holds a Transport security section with two working examples. | file |
| F-042 | The toolchain and both images target Java 21 LTS. | `docker run --entrypoint java` reports `21.0.12 LTS` |
| F-043 | CycloneDX builds the bill of materials. The `security` workflow scans it. Dependabot covers Gradle, Actions, and Docker. | `build/reports/queuebox-0.1.0-SNAPSHOT-sbom.json`, 194 components |
| F-044 | Both base images are pinned by digest. The workflow scans the built image. | `Dockerfile`, `.github/workflows/security.yml` |
| F-045 | A credential field accepts a `file:` reference. The Kubernetes pattern is documented. | `ConfigSecretTest` |

### Exit condition evidence

Command: `./gradlew clean build check`

```
BUILD SUCCESSFUL in 3m 15s
```

Aggregate coverage: line 0.8788, branch 0.7639. Both gates hold.

```
docker build -t queuebox:phase3 .
docker run --rm --entrypoint java queuebox:phase3 -version
openjdk version "21.0.12" 2026-07-21 LTS
```

```
./gradlew sbom --no-configuration-cache
build/reports/queuebox-0.1.0-SNAPSHOT-sbom.json
```

### Decisions recorded during Phase 3

1. **The `Secret` type lives in `core`.** `config` depends on `core`, and every consumer of the
   configuration also depends on `core`, so the type is reachable everywhere it is needed.
2. **The kotlinx serializer writes the mask.** A serialized configuration is therefore not a
   round-trip form. A serializer that wrote the credential would defeat the type, because one
   call to a JSON encoder anywhere would leak every secret.
3. **Some credential parts cannot become a `Secret`.** A JDBC URL, an AMQP URI, and a static
   header map each carry one credential part inside a larger value. The enclosing data classes
   override `toString` and mask those parts, which is what the F-038 fix text asks for.
4. **The publisher follows no redirect.** A 3xx now fails the publish. Without that rule a
   validated public destination could redirect QueueBox to a private address with the
   destination authentication headers attached.
5. **CycloneDX and the configuration cache.** The plugin does not support the Gradle
   configuration cache, so the `sbom` task and the CI step both pass
   `--no-configuration-cache`.

### Deviations from the Definition of Done

- **F-043 and F-044.** The workflow files exist and parse as valid YAML, and every local step was
  run by hand. No CI run proves them green, because that needs a push to GitHub. Phase 5 owns
  the CI findings and will confirm the first run.
- **F-043.** The bill of materials is a workflow artifact. The Definition of Done says a release
  produces `queuebox-<version>-sbom.json`. The name is correct, but no release workflow exists
  yet. F-064 in Phase 5 owns the release process and must attach the file.

### Findings from the audit that were not acted on

- **The startup address check is a time-of-check to time-of-use control.** It resolves the host
  once. The publisher resolves it again per request. A complete control belongs at the network
  egress. `docs/operations/security.md` states the limit plainly.
- **`Secret.of` reads a file with no size bound.** The path is operator configuration that is
  read once at startup, so a hostile value implies the operator is already hostile.

---

## Phase 4 — complete

Plan: `docs/build/phase-04-plan.md`. Commits `b261c3c`, `e86b6d2`.

### Findings closed

| ID | Fix | Evidence |
|----|-----|----------|
| F-046 | SLF4J and Logback replace every `println`. The mapped diagnostic context carries the message-scoped fields. | `grep` returns zero results. `LoggingTest`, `LogContextTest` |
| F-047 | The inbox accepts or generates `X-Correlation-Id`, stores it, and the relay forwards it. The AMQP source carries it too. | `E2ECorrelationTest`, `InboxRoutesTest` |
| F-048 | The roadmap names the target version for tracing and separates intent from a promise. | `README.md` |
| F-049 | `/health/live` does no input or output. `/health/ready` reports the dependencies. | `HealthRoutesTest` |
| F-050 | Contributors cover the poller, the retention service, the relay, and each RabbitMQ source. | `HealthManagerTest` |
| F-051 | `server.managementPort` moves the operational endpoints to a second server. | `ManagementPortTest` |
| F-052 | New bounded counters and a queue depth gauge. | `QueueBoxMetricsTest`, `MetricsRoutesTest` |
| F-053 | The info gauge reads the Gradle version through a generated resource. | `QueueBoxMetricsTest` |
| F-054 | `docs/operations/runbook.md` covers the five scenarios. | `RunbookSqlTest` executes every documented statement |
| F-055 | `docs/operations/dead-letter.md` documents the list and the requeue. | `E2EDeadLetterReplayTest` |
| F-056 | The start waits for the database with backoff. | `DatabaseStartupTest`, `E2EStartupTest` |
| F-057 | Every configured transform compiles at startup. | `StartupValidatorTest` |

### Exit condition evidence

Command: `./gradlew clean build check jacocoAggregatedReport --rerun-tasks`

```
BUILD SUCCESSFUL in 5m 7s
77 actionable tasks: 76 executed, 1 up-to-date
```

```
grep -rn "println(" --include='*.kt' */src/main
0
```

Aggregate coverage: line 0.8800, branch 0.7418. Both gates hold.

### Decisions recorded during Phase 4

1. **The correlation header constant lives in `core`.** `OutboxPoller` needs it for the mapped
   diagnostic context, and `outbox-service` does not depend on `inbox-service`.
2. **The outbox needs no correlation column.** The relay writes the identifier into the outbox
   headers, and every publisher forwards the message headers.
3. **`withLogContext` uses `MDCContext`.** The mapped diagnostic context of SLF4J is thread
   local, and a coroutine can resume on another thread. A put and remove pair loses the context
   and leaks an entry to an unrelated message.
4. **A readiness check runs in the health manager's own scope.** A structured child would make
   the caller wait for a blocking check that ignores cancellation. The abandoned job ends when
   its blocking call returns.
5. **F-048 states a target version.** The document allows either implementation or a qualified
   roadmap entry. Tracing is a feature, and Phase 4 closes operational defects.

### Deviations from the Definition of Done

- **F-046.** The document names 20 `println` occurrences. The tree held 15 when the phase
  started, because earlier phases had already replaced five.
- **F-052.** The Prometheus exporter strips the `_info` suffix, so `queuebox_info` appears in a
  scrape as `queuebox`. The README states it and the test asserts the exposed form.

### Findings from the audit that were not acted on

- **A control character in an HTTP correlation header.** Ktor rejects it before the route sees
  it, so the route filter is the second layer. `InboxRoutesTest` asserts that Ktor rejects it.
  The filter still matters for an AMQP source, where a header value is an arbitrary string.
- **`DatabaseStartup` bounds the sleeps, not the attempts.** The wall time can exceed
  `database.startupTimeoutMs` by one attempt, which is the pool timeout plus the validation.
  `DatabaseStartupTest` asserts what the code guarantees.

### Carried forward

Decision 4 of section 2A requires the inbox accept response to change from 200 to 202. That is
F-078, which belongs to Phase 6. The audit flagged it so it is not lost.

---

## Phase 5 — complete

Plan: `docs/build/phase-05-plan.md`. Commit `6445230` closed Task 1, Task 2 and Task 3. Commits
`0f00401`, `53c106c` and `cf976ce` close Task 4.

**Closed:** F-058 (Phase 1), F-059, F-060, F-061, F-062, F-063, F-064, F-065, F-066, F-067,
F-068, F-069 (Phase 2), F-070, F-071 (Phase 2).

### Task 4 — what landed

- **F-066.** The convention plugin in `buildSrc` applies ktlint and detekt, so both run for every
  module and both join `check`. `.editorconfig` holds the format contract and selects
  `ktlint_code_style = intellij_idea`. The default `ktlint_official` style rewrites almost every
  file, because it adds rules the codebase never followed. `intellij_idea` matches the official
  Kotlin coding conventions, which `CONTRIBUTING.md` already names as the standard.
  `config/detekt/detekt.yml` holds the rule set. Commit `53c106c` is the dedicated format pass.
- **F-067.** Every inline `group:artifact:version` string moved into `gradle/libs.versions.toml`.
  The ktlint engine version and the JaCoCo tool version moved there too. The convention plugin
  reads both through `VersionCatalogsExtension`, because a precompiled script plugin has no
  generated `libs` accessor.
- **F-068.** `gradle/verification-metadata.xml` holds a SHA-256 for every artifact, 528
  components. Verification is active for the main build and for the Docker build, because
  `.dockerignore` does not exclude `gradle/`.

### Phase 5 evidence

```
./gradlew clean build check jacocoAggregatedReport --rerun-tasks
BUILD SUCCESSFUL in 5m 10s, 141 tasks executed

./gradlew ktlintCheck detekt                        BUILD SUCCESSFUL
./gradlew sbom --no-configuration-cache --refresh-dependencies
                                                    BUILD SUCCESSFUL
./gradlew jacocoAggregatedVerification              BUILD SUCCESSFUL
Aggregate coverage: line 0.8898, branch 0.7442

grep for an inline group:artifact:version in *.gradle.kts   -> nothing
git ls-files | grep -E '^\.(taskmaster|cursor|claude)/|^\.mcp\.json$'  -> nothing
grep -rn "maven-publish" --include='*.gradle.kts' .          -> nothing

docker build -t queuebox:phase5-check .             image written, exit 0
```

**Clean-container walkthrough of `CONTRIBUTING.md`.** A `eclipse-temurin:21-jdk` container with no
Gradle and no cache cloned the repository and ran the documented fast-test command.

```
docker run --rm -v "$PWD":/src:ro -w /work eclipse-temurin:21-jdk
  git clone /src /work/queuebox
  ./gradlew --version                 -> Gradle 8.13
  java -version                       -> openjdk 21.0.12 LTS
  ./gradlew :core:test :config:test :outbox-service:test :inbox-service:test
  BUILD SUCCESSFUL in 4m 34s, 32 tasks executed
```

The wrapper installed Gradle, the Foojay resolver was not needed, and dependency verification
passed against a cold cache. This closes the container walkthrough that F-061 owed.

### F-068 tamper proof

Replace the HikariCP SHA-256 in `gradle/verification-metadata.xml` with 64 zeros, then run
`./gradlew :postgres:compileKotlin --refresh-dependencies`.

```
> Dependency verification failed for configuration ':postgres:compileClasspath'
  One artifact failed verification: HikariCP-6.0.0.jar (com.zaxxer:HikariCP:6.0.0)
  This can indicate that a dependency has been compromised.
```

Restore the checksum and the build passes.

### Decisions recorded during Task 4

1. **ktlint uses the `intellij_idea` style, not `ktlint_official`.** The official style reported
   2286 indent violations and 344 multiline-expression-wrapping violations. It would rewrite
   almost every file for a convention the project never adopted.
2. **`WildcardImport` and the matching ktlint rule are off.** The Ktor routing DSL and the
   Exposed SQL DSL are designed around a wildcard import.
3. **`InvalidPackageDeclaration` is off.** The module source roots do not mirror the package. A
   move of every file is a layout change, not a lint change.
4. **`TooGenericExceptionCaught` and `SwallowedException` are off.** With the rules on, 26 sites
   in 15 main source files report, across every module. QueueBox is a message relay, and every
   boundary handler must survive any failure of one message. The audit proposed a narrow
   `excludes` list. The measurement did not support it, so the rules stay off with the real
   reason written down.
5. **A detekt baseline holds the pre-existing structural findings.** 50 entries across eight
   per-module files: 31 MagicNumber, 8 LongMethod, 3 MatchingDeclarationName, 2 ReturnCount, 2
   LargeClass, 1 TooManyFunctions, 1 NestedBlockDepth, 1 LongParameterList, 1 ComplexCondition.
   No correctness rule is baselined. A refactor of shipped logic is not a lint change. One
   baseline per module is required, because a shared file would make two module tasks write the
   same path.

### The audit finding that was acted on

**The verification metadata was incomplete, and it broke the release.** A metadata file generated
from `./gradlew build` alone holds no `.pom` entry for about 49 artifacts. `cyclonedxBom` resolves
a configuration that `build` never resolves, and that resolution reads the `.pom` file. So
`./gradlew sbom`, the release workflow SBOM step and the security workflow SBOM step all failed
verification. The fix is a second generation command, and `CONTRIBUTING.md` now names both.

### Findings from the audit that were not acted on

- **Commit `53c106c` also deletes 21 unused imports.** The commit message calls it a format pass.
  Every deleted symbol is absent from the file body, so no defect follows. The wording is loose,
  not wrong.

### Deviations recorded

- **F-059, F-063.** No GitHub Actions run exists. The workflow files parse as valid YAML and every
  action input was checked against its documented interface, but the GHCR push, the arm64 build,
  the provenance attestation, the release upload and the database matrix are unverified. The
  badges cannot resolve until the workflows reach the default branch.
- **F-061.** The clean-container walkthrough covered the documented fast-test path. It did not
  cover `./gradlew check`, because that needs a Docker daemon inside the container. `./gradlew
  clean build check` passed on the development host instead.
- **F-061.** `CODE_OF_CONDUCT.md` carries the GitHub noreply commit address, which receives no
  mail. The maintainer must replace it with a real inbox. `SECURITY.md` uses the GitHub private
  advisory form, which does work.

### Phase 5 exit condition

`LICENSE`, CI, the templates and the release process are in place. A new contributor goes from
clone to a green build using only `CONTRIBUTING.md`, proved in a clean container above.
**Met, with the two F-059 and F-063 deviations recorded above.**

---

## Phase 6 — complete

Plan: `docs/build/phase-06-plan.md`. Commits `58930c2`, `2b4649c` and `ce67556`.

**Closed:** F-072, F-073, F-074, F-075, F-076, F-077, F-078, F-079, F-080, F-081, F-082, F-084,
F-085. **Not met: F-083**, see the open question below.

### The exit condition

Section 3 of `hardening-doc.md`: the documents are restructured under `docs/`, and every code
sample in every document is executed by a test.

```
./gradlew clean build check jacocoAggregatedReport --rerun-tasks
BUILD SUCCESSFUL in 3m 45s, then BUILD SUCCESSFUL in 3m 46s

./gradlew :app:test --tests 'docs.*'         14 tests PASSED
./gradlew :app:test --tests '*IntegrationDocSqlTest*'   3 tests PASSED
./gradlew ktlintCheck detekt                 BUILD SUCCESSFUL
wc -l README.md                              141, down from 1142
Aggregate coverage: line 0.8966, branch 0.7550

docker compose -f docker-compose.yml --env-file .env.example up -d --build
  GET  /health        -> 200 {"status":"healthy",...}
  POST /inbox/stripe  -> 202 {"messageId":"12f1687e-..."}
  POST /inbox/stripe  -> 200 {"status":"duplicate"}

examples/webhook-receiver/smoke-test.sh  PASS, destination logged POST /webhook and the body
examples/http-fanout/smoke-test.sh       PASS, analytics 1, audit 1, no crossover
examples/rabbitmq-bridge/smoke-test.sh   PASS, queue events-audit holds 1, routing key payment.succeeded

Manual Setup of docs/getting-started.md, run on this host against a clean database:
  Applied 5 migration(s). /health -> 200 {"status":"healthy",...}
```

**Met.**

### The five defects that no finding named

Each one made a documented instruction false, so each was fixed under the finding it broke.

1. **A `QUEUEBOX_` variable never overrode anything.** The builder put the resource source before
   the environment source, so the YAML always won.
2. **Hoplite bound a path on a double underscore.** So `QUEUEBOX_DATABASE_URL` set nothing and
   failed in silence, while every validation error printed that exact name. The source is now a
   map built by `EnvConfigLoader.envKeyToYamlPath`.
3. **A default deployment was unhealthy forever.** Retention is disabled by default, so
   `RetentionService.isRunning` stayed false and the readiness answer stayed 503. No Compose
   health check ever passed.
4. **The packaged configuration merged into every deployment.** Hoplite cascades a map node key by
   key, so an operator whose external file declared one destination also served `/inbox/stripe`
   and `/inbox/github`. Every deployment answered on two HTTP endpoints that its own
   configuration never declared. An external file now replaces the resource.
5. **A document was not an input of the test task.** An edit to a Markdown file alone left
   `:app:test` UP-TO-DATE, so a sample could rot with every test green.

### What the audit found, and what was done

The audit found 13 blocking items. Every one was verified before it was acted on, and every one
was fixed. The largest were defect 4 above, the absence of the mandatory `DocumentedExamplesTest`,
and 21 document defects that the new test then caught.

Two smoke tests were too weak. The fan-out test grepped a shared identifier prefix, so two swapped
routes passed. Swapping the routes now fails, which was checked by mutation. The RabbitMQ queue
bound on `#`, so the routing key was never exercised. It binds on `payment.*` now.

One audit finding was not acted on. The audit asked for a `GuaranteesTest` that links each README
guarantee to its test. The README names the test for each of the five guarantees, and each named
test exists and passes. A test that parses prose to find a test name adds a second place to break.
This is a deviation, recorded, not an oversight.

### Decisions recorded during Phase 6

1. **A route matches one destination.** `MessageRouter` uses `firstOrNull`. Two routes with the
   same pattern do not duplicate a message. F-081 asks for a fan-out example, and the product does
   fan-out by writing one outbox row per destination topic. Adding a true one-to-many route is a
   feature, and this phase adds none. The example states the real rule.
2. **An external configuration file is complete.** It replaces the packaged resource. A partial
   overlay is what caused defect 4.
3. **`MessageState.canTransitionTo` is deleted.** It had no caller and it contradicted the code.
4. **`docker-compose.override.yml` keeps its name.** It is auto-loaded and holds the development
   loop, so the documented quick start passes `-f docker-compose.yml`. `hardening-doc.md` treats
   the override as a legitimate file, and a rename is a redesign.

### Known flakes

Recorded rather than hidden. Each one passed on a rerun and in isolation, and each failed only
under the load of a full build that starts many containers at once.

- `IntegrationDocSqlTest > every postgres statement runs...` failed once. The diagnostic now
  reports the `processing` count and the insert block count, so a repeat is diagnosable.
- `E2EShutdownTest` failed once on a 10 second latch. The latch is the right signal and the budget
  was too tight, so the budget is 60 seconds. The assertion is unchanged.
- `E2EDeadLetterReplayTest` and `E2EInboxFlowTest` failed once together. The second reported
  uncaught exceptions BEFORE the test started, which is a test-infrastructure symptom rather than
  an assertion failure. Watch both in CI.


---

## Section 11 — the Definition of Done for the whole effort

Every item carries its evidence. Item 6 is the findings index above. Item 15 is the timed
walkthrough below.

| # | Item | Result |
|---|------|--------|
| 1 | `clean build check jacocoAggregatedReport` on Java 21 with Docker | **Met.** BUILD SUCCESSFUL, repeatedly. |
| 2 | Aggregate 80 percent line, 70 percent branch, no module below 60 | **Met.** Line 0.9033, branch 0.7576. The lowest module is `postgres` at 0.80. The sixth review gate found that `check` never ran the aggregate rules; it does now, proved by raising the floor to 0.99 and watching the build fail. |
| 3 | No `println` in a main source | **Met.** The grep returns 0. |
| 4 | No `TODO`, `FIXME` or `Hello, Kotlin` | **Met.** The grep returns 0. |
| 5 | `ktlintCheck detekt` | **Met.** BUILD SUCCESSFUL. |
| 6 | Every finding closed or deferred with a rationale | **Met** for F-001 to F-082 and F-084, F-085. F-083 is open, see the question below. |
| 7 | Compose delivers to an HTTP and a RabbitMQ destination, proved by CI | **Met.** The `compose` job asserts both. Both halves were also run on this host. |
| 8 | Every fenced YAML block loads and validates | **Met.** `DocumentedExamplesTest`. |
| 9 | Every documented metric is in a live scrape | **Met.** `MetricsDocTest`, with an empty allowlist. |
| 10 | Every documented status code is produced by a test | **Met.** The inbox set, the health endpoints, the metrics endpoint, the admin route and `GET /`. |
| 11 | The governance files exist | **Met.** LICENSE, SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md, CHANGELOG.md, .github/CODEOWNERS, the issue templates and the pull request template. |
| 12 | CI runs build, test, lint, coverage, image build and image scan | **Met** in the workflow files. **Unverified** until they run on the default branch. |
| 13 | A tagged release publishes an image and an SBOM | **Met** in `release.yml`. **Unverified**, see F-063. |
| 14 | No agent tooling directory is tracked | **Met.** The grep returns nothing. |
| 15 | Clone to a delivered message in under 10 minutes, README only | **Met. 172 seconds.** |

### Item 15, the transcript

A clean clone, from the committed state, with no warm cache beyond the Docker layer cache.

```
git clone . <tmp>/queuebox && cd <tmp>/queuebox
docker compose -f docker-compose.yml --env-file .env.example up -d --build
curl http://localhost:8080/health
  -> 200 {"status":"healthy","components":{...}}
curl -X POST .../inbox/stripe -d '{"id":"evt_1","type":"payment.succeeded"}'
  -> 202 {"messageId":"3aa78223-..."}
  -> 200 {"status":"duplicate"}   (the same request again)
docker compose logs receiver
  -> delivered POST /webhook {"id":"evt_1","type":"payment.succeeded"}
ELAPSED: 172 seconds
```

The host published PostgreSQL on 5432 already, so the run dropped that port mapping. Nothing else
was changed.

---

## The adversarial review gate

Section 11 ends with a gate: an independent model reviews the repository and the gate passes only
when the review produces no confirmed finding. It ran repeatedly, and it is the most productive
part of the whole effort.

| Pass | Confirmed blocking | What it found |
|------|--------------------|---------------|
| 1 | 4 of 5 | A lost message on an AMQP transform rejection. The broker password in the log and in `outbox.last_error`. The relay was not atomic, which F-002 requires. Two false claims about `Secret` and `file:`. |
| 2 | 3 | A redelivery left a rejected row `pending`, so the relay forwarded it. Four more credential shapes escaped the sanitiser. A missing idempotency key stored a duplicate row on every redelivery. |
| 3 | 9 | The rejected-row fix was still racy. An invalid AMQP URL printed the password to stderr. `outbox.maxAttempts` was documented and never read. The inbox `COUNT` policy was accepted and did nothing. Nine log sites passed the raw throwable to SLF4J. |
| 4 | 3 | **A fix from pass 2 destroyed healthy messages.** An indefinite JSONPath lost every message silently. The documented manual schema rejected every inbox insert. |
| 5 | 1 | A default AMQP source destroyed every message whose publisher omitted an undocumented header. |
| 6 | 3 | **The aggregated coverage gate had never run.** The startup validator printed a destination password. The database retry line printed the database password on every slow start. |
| 7 | 4 | **The pass 6 coverage fix was wrong, and so was my proof of it.** No terminal write was fenced, so a stale owner could complete another replica's claim. The database password still reached the log through the exception cause. The pool opened outside the guarded path. Plus fifteen documentation claims the code does not perform. |
| 8 | 4 | Three more credential shapes escaped the redaction, including a malformed URI with one slash. `docs/architecture.md` carried a sentence, written one commit earlier, that the code contradicts. |
| 9 | 2 | Both findings were OVER-redaction caused by the pass 8 fix. The whole error message was destroyed, and an ordinary sentence lost a word. |
| 10 | 3 | **SQL Server was documented and never shipped.** The pass 9 repair reopened the leak it bounded: a password with whitespace AND a comma matched no shape at all. |
| 11 | 4 | The configuration load was the one unguarded startup call, so a malformed YAML printed a password line. A disabled inbox relay held readiness at 503 for ever. A third check was wired and never ran. Two documents denied a dependency the build declares. |
| 12 | 3 | A password holding whitespace AND a `#` escaped the mask. A fourth check was wired and never ran. The documented inbox step order does not match the code. |

**Forty-five confirmed blocking defects, every one reproduced before it was acted on.** Four of
them were introduced by an earlier fix in this same effort.

The gate did NOT pass. Twelve passes ran and every one produced at least one confirmed finding.
The effort stopped at the maintainer's instruction, not at a clean pass. The open findings are
listed under "What the gate still owes" below.

### What the gate teaches

1. **A fix is a change, and a change needs its own review.** Pass 4 found that the `markDeadByKey`
   method added in pass 2 had no state guard, so a rejected message could kill an unrelated healthy
   row. The first repair proposed for it, a `pending` guard, was also wrong: with `storeDead`
   writing atomically, a `pending` row of that key is a different healthy event. The correct answer
   was to delete the mark, because nothing needed repair any more.
2. **A silent default is worse than a loud failure.** Three of the twenty-one were a configuration
   that QueueBox accepted and then ignored or acted on wrongly: the inbox `COUNT` policy,
   `outbox.maxAttempts`, and the AMQP default topic. Each looked healthy at startup.
3. **A comment is not evidence.** `InboxHandler` carried a comment saying the rejection reason is
   never the path, above code that put the path in the reason. `ExposedTransactionRunner` claimed a
   nested repository call joins its transaction. Neither was true.
4. **A proof is only as good as the state it ran in.** I wired the coverage gate into `check` in
   pass 6 and proved it with a mutation that failed the build. Pass 7 showed the proof was
   worthless: it ran on a WARM tree. The root build collected the exec files eagerly at
   configuration time, so the list held the PREVIOUS build. On a clean checkout, which is every CI
   run, the list is empty and JaCoCo skips a report with no data. The reviewer was right and I was
   wrong, and it was right for a reason I had not tested rather than a claim it repeated. The
   collections are lazy now, and the proof runs from zero exec files.
5. **A gate that nobody runs is not a gate.** Pass 6 found that `check` never ran the aggregated
   coverage rules, although `README.md` and `TESTING.md` both said it did, and the CI job ran the
   report rather than the verification. Every earlier "check passes" line in this file therefore
   proved less than it appeared to. The numbers were real, because the verification task was run
   by hand several times, but the claim about `check` was false. The task is wired in now, and the
   proof is a mutation: raise the floor to 0.99 and the build fails.
6. **A repair of a redaction is itself a defect source.** The sanitiser pair was attacked in six
   passes and defeated in five, and FOUR of the repairs created a new defect. A rule that bans a
   character bans it everywhere, including inside the secret it must hide. A rule loose enough to
   catch every secret destroys the message an operator needs. The answer was not a better regular
   expression. It was to stop the credential entering the text at all: `RabbitConnection` now
   masks the AMQP URI where the driver rejects it, and the sanitiser is the second layer.
7. **A capability can be documented for a whole effort and never ship.** Ten passes ran before one
   asked whether the SQL Server module reaches the RUNTIME class path. It did not. Reflection hid
   the break from the compiler, and every test passed because the TEST class path carried the
   module. A check that reads the runtime configuration now fails the build, and that check is
   mutation proved, because its first version was satisfied by a look-alike artefact name.
8. **Redaction needs an adversary.** The sanitiser was defeated three times: on a URL password, on
   an underscore key, on a bare `Basic` scheme, on a nested cause, on two spaces and a tab, and on
   a slash inside a password. Each round added a test.

### Deviations recorded

- **A URL password that holds whitespace, with no port and no path, and a slash inside it, is
  still not masked.** The rule masks when the text is ambiguous, so the leak is narrow. The
  alternative destroys a prose error message. The trade is recorded in `CredentialMasking`.
- **The body digest deduplicates two distinct events that carry the same body.** That is the last
  resort when the publisher supplies no key at all. The alternative, a random key, never
  deduplicates. `docs/configuration.md` and `docs/message-flow.md` both tell the operator to set a
  key source.

---

## What the gate still owes

The twelfth pass reported three blocking findings. All three are fixed in the final commit. The
pass also reported items it confirmed by reading and could NOT reproduce with a failing test. The
gate rule makes those non-blocking, and they are recorded here because nobody has closed them.

**1. SQL Server aggregate ordering. The strongest open item.**
`sqlserver/src/main/kotlin/org/nxtspec/SqlServerInboxRepository.kt` releases the session
application lock in a `finally` block INSIDE the transaction, so the release runs BEFORE the
commit. A second replica can then take the lock, see no `processing` row for that aggregate, and
claim a second message of the same aggregate. PostgreSQL uses `pg_advisory_xact_lock`, which holds
to commit, so PostgreSQL is not affected. `docs/message-flow.md` states the guarantee absolutely,
for both databases. The reviewer could not build the interleaving without editing production code.
**Treat the per-aggregate ordering guarantee as unproven on SQL Server until this is settled.**

**2. Two credential shapes with no sink today.** A JDBC connection string of the form
`password=my secret;` keeps its tail, because the value pattern stops at whitespace, and
`DestinationAuthConfig.OAuth2.extraParams` is a plain map that no mask covers. Neither reaches a
log in the shipped application: the second is blocked by `PublisherRegistry`. A future call site
would open both.

**3. Two shipped SQL comments drifted.** `V1__create_outbox.sql` names a `failed` state that no
repository writes, and `V2__create_inbox.sql` omits `dead`, which `storeDead` writes. A migration
is immutable once released, so the correction needs a new version rather than an edit.
`DocumentedStateSetTest` guards the Markdown and not the shipped SQL.

**4. Two documentation slips.** `docs/getting-started.md` gives a `--profile rabbitmq` command
without `-f docker-compose.yml`, nine lines after it explains that `-f` is required, so that one
command starts the development loop. `docs/development/releasing.md` and
`docs/development/building.md` say the release ATTACHES the provenance attestation; the workflow
attaches the SBOM and pushes the provenance to the registry.

**5. The gate never passed.** Twelve passes, every one with a confirmed finding. The rate did not
reach zero. A thirteenth pass would probably find something, and the honest reading of that is
that this codebase rewards continued adversarial review rather than that it is now perfect.

## Post-hardening effort — the claim contract, capture and evidence

This effort is not part of `hardening-doc.md`. It was requested separately and delivered in three
commits on `main`: `6c546b3`, `f5258db` and `982ad9a`. The repairs that the first live workflow
runs then demanded follow in `cbb5876`, `f83b8f3`, `45cf371` and `5b9f544`, and `97ccdd0` prepared
the release.

### What it added

| Change | Evidence |
|--------|----------|
| Per-source `consumption: push` or `pull`, stored on the row at receipt. The relay claims push rows only. | `PullLeaseTest`, `SqlServerPullLeaseTest`, `CaptureConfigTest` |
| An opaque claim token and a lease replace the timestamp fence. Every terminal write is fenced inside SQL against the database clock. | `PullLeaseTest`, `ClaimFenceTest`, `SqlServerClaimFenceTest`, `CustomTableNameTest` |
| `withClaimLease` renews while work runs and cancels the work when ownership is lost. | `ClaimLeaseTest` |
| A conflated delivery signal replaces the fixed poll interval when capture is on. | `DeliverySignalTest` |
| An embedded change data capture connector wakes delivery. Off by default. | `CaptureIntegrationTest` on real PostgreSQL 16 and SQL Server 2022, `CapturePropertiesTest`, `examples/cdc` in CI |
| Capture state is bound to a fingerprint of its settings, and the PostgreSQL slot and the SQL Server capture tables are checked before the connector starts. | `CaptureIntegrationTest`, `CapturePropertiesTest` |
| V6 adds the claim columns, V7 the capture registry. Both are additive. | `PostgresMigratorTest`, `SqlServerMigratorTest`, `MigrationParityTest` |
| A benchmark harness, its results, and `docs/development/verification-report.md`. | `benchmarks/` |

### Defects that this effort found in existing code

1. **The SQL Server wake query mixed two clocks.** `scheduled_at` is written from the application
   clock and `lease_expires_at` by `SYSUTCDATETIME()`. Comparing one against the other delayed
   every scheduled retry to the reconciliation interval on any host that does not run in UTC. A
   new test case found it on a UTC+2 host.
2. **Capture health failed readiness.** A capture fault would have taken a delivering instance out
   of service. Readiness now carries advisory components, and capture is one.
3. **The test harness sat inside the configuration namespace.** The database matrix selects its
   image through a `QUEUEBOX_*` variable, which `EnvConfigLoader` reads as configuration, so every
   matrix job failed the first time the matrix ran. `DocumentedExamplesTest` held a local allowlist
   that the loader never shared. The harness variables are now `TESTCONTAINERS_*`, and a new check
   applies the binding rule to the workflow files.
4. **`aquasecurity/trivy-action@0.28.0` never resolved**, because those tags carry a `v` prefix.
   Neither the dependency scan nor the image scan had ever run.
5. **Two end-to-end tests raced.** One used `return@repeat`, which continues a loop rather than
   leaving it, so it always waited to the end and could count a redelivery. The other waited for a
   persisted error before asserting that the destination had received a secret, which a first
   attempt that never reached the destination satisfies.
6. **The bill of materials described the test harness.** The document covered every configuration,
   so a scan of it blocked a release on a vulnerability in code that never ships, and it hid the
   shipped set in the noise. It now covers the runtime class path.
7. **The shipped set carried 42 HIGH and CRITICAL advisories.** The scan had never run, so nobody
   had seen them. Most arrived with the capture connector, which brings the Kafka Connect runtime,
   and that runtime brings Jetty. Four direct dependencies moved to fixed releases and the rest are
   raised by constraints, with the floors in the version catalogue. The base image still lagged the
   Alpine security branch, so the runtime stage upgrades the three TLS packages.
8. **A document test bound one dialect to the other's database.** Exposed resolves a suspended
   transaction against one global default, and that test drives both dialects in one process. A
   PostgreSQL repository reached a SQL Server connection and sent `clock_timestamp()` to it. The
   delivery arrived, the terminal write failed, and the row went back to pending. The failure read
   as a slow runner and was not.

### The release

`v0.1.0` was cut from `97ccdd0` after `ci` and `security` both passed on `main`. The release
workflow published the image for both architectures, attached the bill of materials, and pushed
the provenance attestation. See open question 2 below.

### What this effort did not prove

`docs/development/verification-report.md` carries the list. In short: the benchmark ran five,
three and two runs rather than five each; the low-rate latency phase and the slow-receiver phase
were not run; the figures are PostgreSQL only, because SQL Server is emulated on the measuring
host; and capture mode is proved for insert delivery and the scheduled retry, while scheduling,
retry, dead-lettering, replay, retention and custom mappings are proved in polling mode.

## Next phase

**All six phases are complete, the post-hardening effort above is delivered, and `v0.1.0` is
released.** The remaining work is the maintainer's, below.

## Open questions for the maintainer

**1. F-083 is now met, except for a measured coverage figure.** The repository is public, and it
carries the suggested description and the eleven suggested topics. The build, security, release,
license and coverage badges resolve. The coverage badge still states the gate that CI enforces
rather than a measured figure, because the project has adopted no coverage service.

**2. F-063 is closed. The release ran and needed no extra permission.** The `packages: write`
that the job already declared was enough for the GHCR login, so no maintainer grant was needed.
The tag `v0.1.0` published `ghcr.io/alternayte/queuebox` for `linux/amd64` and `linux/arm64` under
the three tags `0.1.0`, `0.1` and `latest`, attached the bill of materials to the GitHub release,
and pushed the provenance attestation to the registry.

The package is public. An anonymous pull of each of the three tags answers 200, so no visibility
change was needed either.

**3. Every workflow has now run on `main`, the release included.** Live runs exposed defects that
no amount of reading could: the database matrix collided with the configuration namespace, the
trivy action reference never resolved, the bill of materials described the test harness rather
than the shipped set, and a document test bound a PostgreSQL repository to a SQL Server
connection. Each is fixed in `cbb5876`, `45cf371` or `5b9f544`.

The database matrix used to run the whole suite once per entry, which starved five two-core
runners at the same time and failed different unrelated tests in each. The suite now runs once,
and the compatibility jobs run the repository tests of one dialect against each older version.

**4. `CODE_OF_CONDUCT.md` carries the GitHub noreply commit address**, which receives no mail. It
needs a real inbox.

**5. The breaking changes need a release note, and one of them needs an operator step.**
`CHANGELOG.md` carries them all under `### Breaking changes`. `retention.inbox.policy: COUNT` now
fails the startup, the RabbitMQ source default topic changed from `{{ eventType }}` to
`{{ source }}`, and `outbox.maxAttempts` now reaches the message. None of those had a working
behaviour before, so no deployment loses one.

The V6 migration is different, and it is the one to put in front of a reader. It is additive, but
the old timestamp claim fence and the new token fence must never run at the same time, because an
old worker can complete a row that a new worker owns. Anyone running a pre-release build must stop
every worker, apply V6 and V7, then start the new workers.
