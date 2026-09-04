# QueueBox Hardening — Build Status

Source of truth: `hardening-doc.md`. The document is immutable and authoritative.

## Phase table

| Phase | Title | Findings | Status |
|-------|-------|----------|--------|
| 1 | Truth in advertising | F-001 to F-012 | **complete** (commit `eeb1d00`) |
| 2 | Durability and correctness | F-013 to F-033 | **complete** (commits `93afae4`..`8960a00`) |
| 3 | Security hardening | F-034 to F-045 | **complete** (commits `fb1f01f`..`f3fc59d`) |
| 4 | Observability and operations | F-046 to F-057 | **complete** (commits `b261c3c`..`e86b6d2`) |
| 5 | Open source governance | F-058 to F-071 | in progress. F-058, F-059 to F-065, F-069, F-070, F-071 closed. F-066, F-067, F-068 remain. |
| 6 | Documentation and polish | F-072 to F-085 | not started |

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

## Next phase

**Phase 6 — Documentation and polish, F-072 to F-085.** Exit condition: see section 3 of
`hardening-doc.md`. F-012 and F-071 are already closed. F-078, which changes the inbox accept
response from 200 to 202, is carried forward from the Phase 4 audit.
`docs/build/phase-06-plan.md` does not exist yet.

## Open questions for the maintainer

**One operational item stays open.** F-063 needs the GHCR permissions that only the maintainer
can grant, so the release workflow stays unproven. Section 2A decision 2 settles the artifact
question: a container image only, no Maven artifacts and no signing. The remaining item is
operational, not a product decision, so Phase 5 recorded it as unverified rather than stop.
