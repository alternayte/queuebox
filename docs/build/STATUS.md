# QueueBox Hardening — Build Status

Source of truth: `hardening-doc.md`. The document is immutable and authoritative.

## Phase table

| Phase | Title | Findings | Status |
|-------|-------|----------|--------|
| 1 | Truth in advertising | F-001 to F-012 | **complete** (commit `eeb1d00`) |
| 2 | Durability and correctness | F-013 to F-033 | **complete** (commits `93afae4`..`8960a00`) |
| 3 | Security hardening | F-034 to F-045 | **complete** (commits `fb1f01f`..`f3fc59d`) |
| 4 | Observability and operations | F-046 to F-057 | not started |
| 5 | Open source governance | F-058 to F-071 | not started. F-071 is already closed. |
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

## Next phase to start

**Phase 4 — Observability and operations, F-046 to F-057.** Exit condition: structured logging
replaces every `println`, graceful shutdown proven by test, runbook published.

`docs/build/phase-04-plan.md` does not exist yet.

## Open questions for the maintainer

None. No question blocks Phase 4.
