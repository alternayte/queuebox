# Testing and Code Coverage

This document describes the testing strategy and code coverage standards for QueueBox.

## Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :inbox-service:test
./gradlew :outbox-service:test

# Run tests with coverage report
./gradlew testWithCoverage
```

## Code Coverage

QueueBox uses [JaCoCo](https://www.jacoco.org/jacoco/) for code coverage analysis.

### Coverage Targets

| Metric | Target |
|--------|--------|
| Aggregated line coverage | 80% |
| Aggregated branch coverage | 70% |
| Per-module line coverage | 60% |

`./gradlew check` fails when a value drops below its target. That is intended. It blocks a merge
that adds undertested code.

### Running Coverage Verification

```bash
# Run tests and verify coverage thresholds
./gradlew checkCoverage

# Run module-level coverage verification (runs as part of check)
./gradlew check

# Generate aggregated coverage report only
./gradlew jacocoAggregatedReport
```

### Viewing Coverage Reports

After running tests with coverage, reports are available at:

- **Aggregated report**: `build/reports/jacoco/aggregated/html/index.html`
- **Per-module reports**: `<module>/build/reports/jacoco/test/html/index.html`

### Excluded Classes

The same exclusion list applies to the per-module reports and to the aggregated report. Each
entry names one reason. Add an entry only when the class carries no testable logic, or when only
a running process can execute it.

| Pattern | Reason |
|---------|--------|
| `**/*Table.class` | An Exposed table definition. It declares columns and indexes, and it holds no branch. The integration tests exercise every column through the repositories. |
| `**/*Tables.class` | The same, for the file that declares more than one table. |
| `**/AppKt.class` | The process entry point. `main` wires the whole application together and then blocks on the HTTP server, so only a started process runs it. Every part that `main` wires has its own test: `ShutdownSequenceTest`, `PublisherRegistrationTest`, `MetricsCollectorTest`, `HealthRoutesTest`, `AdminRoutesTest`, and the end to end tests under `app/src/test/kotlin/e2e`. |
| `**/AppKt$*.class` | The lambdas that `main` declares, for the same reason. |

The `**/MainKt.class` exclusion is gone. Finding F-071 deleted the five template `Main.kt` files
and the `utils` module, so the duplicate class no longer exists.

## Continuous Integration

The `./gradlew check` task includes coverage verification and will fail if coverage drops below the configured thresholds. This prevents merging of undertested code.

For CI pipelines, use:

```bash
./gradlew clean check jacocoAggregatedReport
```

This will:
1. Run all tests
2. Verify per-module coverage thresholds
3. Verify aggregated coverage thresholds
4. Generate the aggregated coverage report
