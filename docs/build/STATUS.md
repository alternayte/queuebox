# QueueBox Hardening — Build Status

Source of truth: `hardening-doc.md` (immutable work order).

## Phase table

| Phase | Title | Findings | Status |
|-------|-------|----------|--------|
| 1 | Truth in advertising | F-001 to F-012 | in-progress |
| 2 | Durability and correctness | F-013 to F-033 | not started |
| 3 | Security hardening | F-034 to F-045 | not started |
| 4 | Observability and operations | F-046 to F-057 | not started |
| 5 | Open source governance | F-058 to F-071 | not started |
| 6 | Documentation and polish | F-072 to F-085 | not started |

## Baseline

- Commit at start: `448944e`.
- `./gradlew compileKotlin compileTestKotlin` passes. Verified on 2026-09-03.
- Docker daemon available, so Testcontainers integration tests can run.

## Current phase

Phase 1. Plan: `docs/build/phase-01-plan.md`.

## Next phase to start

Phase 1 must complete first.
