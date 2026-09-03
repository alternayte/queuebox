# QueueBox Hardening — Build Status

Source of truth: `hardening-doc.md`. The document is immutable and authoritative.

## Phase table

| Phase | Title | Findings | Status |
|-------|-------|----------|--------|
| 1 | Truth in advertising | F-001 to F-012 | **complete** (commit `eeb1d00`) |
| 2 | Durability and correctness | F-013 to F-033 | not started |
| 3 | Security hardening | F-034 to F-045 | not started |
| 4 | Observability and operations | F-046 to F-057 | not started |
| 5 | Open source governance | F-058 to F-071 | not started |
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

## Next phase to start

**Phase 2 — Durability and correctness, F-013 to F-033.** Exit condition: all major
findings closed, and new integration tests for crash recovery, concurrency and
retention pass.

Start with `docs/build/phase-02-plan.md`, which does not exist yet.

## Open questions for the maintainer

None. No question blocks Phase 2.
