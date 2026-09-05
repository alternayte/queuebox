# QueueBox verification report

This report records what was verified for the inbox claim contract, the embedded change
data capture, and the delivery performance. It states the command, the environment and
the result of each check, and it names every claim that no evidence supports.

## Environment

| Item | Value |
| --- | --- |
| Machine | Apple Silicon, macOS, ARM64 |
| Container runtime | Docker Desktop |
| PostgreSQL | `postgres:16`, started with `wal_level=logical` where capture is used |
| SQL Server | `mcr.microsoft.com/mssql/server:2022-latest`, under emulation on this host |
| Java | Project toolchain 21 |

SQL Server runs under emulation on this machine. Its timings are therefore not
comparable with a native host, which is why the benchmark uses PostgreSQL only.

## Automated verification

Command:

```text
./gradlew clean check jacocoAggregatedReport --continue --console=plain
```

Result:

```text
BUILD SUCCESSFUL in 2m 13s
146 actionable tasks: 34 executed, 112 from cache
```

Most test tasks were restored from the build cache in that run, so the container tests
were executed again without the cache, to prove that they pass against this exact code:

```text
./gradlew :core:cleanTest :postgres:cleanTest :sqlserver:cleanTest :capture:cleanTest \
          :core:test :postgres:test :sqlserver:test :capture:test \
          --no-build-cache --continue --console=plain

BUILD SUCCESSFUL in 1m 27s
```

Both CDC integration tests, both pull lease suites and both claim fence suites passed in
that run against real PostgreSQL 16 and SQL Server 2022 containers.

The run covers both database modules against real containers, the embedded capture
module against both real connectors, the application and RabbitMQ tests, static
analysis, lint, and coverage verification. No gate, baseline or coverage threshold was
weakened, and no required integration test was skipped or tagged out.

## The inbox claim contract

| Claim | Evidence |
| --- | --- |
| Push and pull are persisted at receipt and isolate each other | `PullLeaseTest`, `SqlServerPullLeaseTest`: *persisted consumption isolates push and pull including duplicate receipts* |
| Concurrent workers never claim the same row | `PullLeaseTest`: *pull claims are disjoint across concurrent workers* |
| An expired or stale token cannot complete, retry, renew or dead-letter | `PullLeaseTest`: *expired token cannot renew complete retry or dead letter reclaimed work*, *expired outgoing ownership cannot mutate even before reclaim* |
| A pull completion and the business write commit or roll back together | `PullLeaseTest`: *completion and business writes commit or roll back together* |
| A null token never bypasses the fence, and renewal protects ownership | `PullLeaseTest`: *outgoing renewal protects ownership and null tokens never bypass the fence* |
| Retention never deletes active work | `PullLeaseTest`: *retention rejects active work* |
| Slow work renews, and a lost claim cancels the work | `ClaimLeaseTest` |
| A stale owner cannot write after a reclaim and a new claim | `ClaimFenceTest`, `SqlServerClaimFenceTest` |

Both `PullLeaseTest` classes execute the published SQL of `examples/pull` directly, so
the documented contract and the tested contract are the same text. The files are declared
Gradle test inputs, so a change to an example re-runs the tests.

Every lease comparison happens inside SQL against the database clock, `clock_timestamp()`
on PostgreSQL and `SYSUTCDATETIME()` on SQL Server. No ownership decision reads a
timestamp back through JDBC, so the time zone that a driver applies to `DATETIME2` cannot
affect fencing.

The two SQL Server deadline columns do not share one clock, and the first version of the
wake query compared them as if they did. `scheduled_at` is written through the driver from
the application clock, and `lease_expires_at` is written by `SYSUTCDATETIME()`. Comparing
`scheduled_at` against `SYSUTCDATETIME()` put the wake off by the offset of the application
time zone, which delayed a scheduled retry to the reconciliation interval on any host that
does not run in UTC. The new scheduled-retry case of `CaptureIntegrationTest` found it on a
UTC+2 host. Each deadline is now measured against its own clock, and the nearer of the two
wins.

## Change data capture

| Claim | Evidence |
| --- | --- |
| Capture wakes delivery before reconciliation would | `CaptureIntegrationTest`, both databases: the reconciliation timer is 60 seconds and the delivery is asserted within 15 |
| A scheduled retry wakes delivery from its deadline, not from reconciliation | `CaptureIntegrationTest`: the retry is asserted within 20 seconds of a 60-second timer |
| Updates and state changes create no delivery | `CaptureIntegrationTest`: *State updates must not create deliveries*; `CapturePropertiesTest`: *only inserts and snapshot records wake delivery* |
| Capture survives a restart and continues from the recorded position | `CaptureIntegrationTest`, both databases |
| A second owner of one identity is refused, and it keeps delivering through SQL | `CaptureIntegrationTest`; `examples/cdc/smoke-test.sh` proves the same for the packaged image |
| Lost offsets require an explicit recovery, and SQL delivery continues | `CaptureIntegrationTest` |
| A lost SQL Server schema history requires an explicit recovery | `CaptureIntegrationTest`, SQL Server branch |
| Changed capture settings never reuse the recorded offsets | `CaptureIntegrationTest`, both databases; `CapturePropertiesTest`: *the fingerprint follows the settings that the offsets belong to* |
| A dropped PostgreSQL replication slot requires an explicit recovery | `CaptureIntegrationTest`, PostgreSQL branch |
| The state fingerprint carries no secret | `CapturePropertiesTest`: *the fingerprint never carries a secret* |
| An unreadable capture record still wakes delivery | `CapturePropertiesTest` |
| Connection overrides and URL parsing reach the connector | `CapturePropertiesTest`, both databases |
| Capture settings load and validate | `CaptureConfigTest` |

Capture contributes an advisory readiness component. A capture fault reports `down` for
`outbox-capture` and leaves the readiness answer healthy, because SQL delivery continues.
`HealthManagerTest` proves it.

The connector needs its own database objects, and QueueBox checks them before it starts:
the publication on PostgreSQL, and change data capture on the database and the outbox
table on SQL Server. QueueBox never creates a publication and never drops a slot.

`examples/cdc` is a runnable stack, and its smoke test is the packaging proof. It asserts
that the built image delivers within 15 seconds against a 30-second reconciliation timer,
that the capture state on a named volume survives a restart, that the distribution ships
both connectors, that a second process with the same identity is refused, and that the
refused process still delivers through SQL. The `examples` job of the CI workflow runs it.

The smoke test has not been executed in this session.

## Performance

The harness is `benchmarks/run.py`. It measures three delivery paths on this machine
against the same `postgres:16` image, with the same receiver, producer, event size, pool
size, batch size and concurrency. Only the delivery path differs.

- `baseline`: the polling delivery before this change, built from `git archive HEAD`.
- `polling`: the polling delivery after this change.
- `cdc`: the change data capture delivery after this change.

Each event carries a payload of about one KiB. Latency is measured from the moment the
producer's batch commit acknowledged to the moment the receiver logged the delivery, both
on the same host clock.

Runs are fewer than the five that the plan asked for: five for `baseline`, three for
`polling` and two for `cdc`. The session that produced them was interrupted, and the
remaining runs were not repeated. Read the medians below with that in mind.

| Events | Batch | Variant | Runs | Throughput per second | p50 ms | p95 ms | p99 ms | Idle transactions per minute | Peak RSS MiB | CPU seconds | Duplicates |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100000 | 1000 | baseline | 5 | 482.4 | 97059 | 184084 | 191505 | 1103.0 | 215.2 | 160.7 | 0 |
| 100000 | 1000 | polling | 3 | 500.8 | 87937 | 172422 | 186167 | 1138.0 | 226.3 | 225.7 | 0 |
| 100000 | 1000 | cdc | 2 | 408.1 | 137679 | 222783 | 229942 | 695.5 | 628.3 | 230.7 | 0 |

Every run of every variant delivered all 100,000 events, and no run produced a duplicate.

**Polling did not regress.** The new polling path is about four percent faster than the
baseline, which is inside the ten percent threshold that would demand an investigation.

**Read the latency column carefully.** The producer inserts far faster than any variant
delivers, so this phase measures how quickly a backlog drains, not how quickly one
committed row reaches the receiver. Every latency here is queueing delay. The wake
latency that capture improves is proved by test instead: `CaptureIntegrationTest` sets the
reconciliation timer to 60 seconds and asserts the delivery within 15, on both databases.

**Capture costs throughput and memory, and it saves idle database load.** Under a full
backlog the wake signal adds nothing, while the connector competes for the same CPU and
holds its own buffers: throughput falls about 19 percent against the new polling path, and
the peak resident memory rises from 226 MiB to 628 MiB. The idle load falls from 1138 to
696 transactions per minute. Capture is therefore a latency and idle-load feature, not a
throughput feature, and the default stays `polling`.

The raw event files of every run stay under `benchmarks/results`, and the per-run result
files are committed beside this report.

## What this report does not prove

- **The benchmark is short.** Five, three and two runs, not five each. The interrupted
  runs were not repeated.
- **No low-rate latency measurement.** `benchmarks/README.md` documents the phase and the
  runner supports it, but it was not run. The commit-to-receipt latency of an idle system
  is therefore not measured, only reasoned about and proved by test as a bound.
- **No slow-receiver or backlog-recovery measurement.** The runner supports it through
  `--delay-ms`; it was not run.
- **PostgreSQL only.** SQL Server runs under emulation on this host, so a number from it
  would say more about the emulator than about QueueBox.
- **The CDC example smoke test was not executed here.** Its assertions are written and CI
  runs them; this session did not.
- **No failover or reconfiguration measurement.** Both are manual by design, and the
  procedure in `docs/capture.md` has not been rehearsed against a production-sized volume.
- **No replay or disconnect test for capture.** A dropped connection is handled by a
  monitor in `OutboxCapture` that no test exercises directly.
- **The non-polling proofs of scheduling, retry, dead-lettering, replay, retention and
  custom mappings run in polling mode.** Capture mode is proved for insert delivery and
  for the scheduled retry only.
