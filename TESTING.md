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
| Aggregated line coverage | 72% |
| Aggregated branch coverage | 65% |
| Per-module minimum | 15% |

> **Note**: These are intermediate targets. Consider raising aggregated targets to 80% line / 70% branch as test coverage improves.

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

The following patterns are excluded from aggregated coverage reports:

| Pattern | Rationale |
|---------|-----------|
| `**/MainKt.class` | Application entry points |
| `**/*Table.class` | Exposed table definitions |
| `**/*Tables.class` | Exposed table definitions |

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
