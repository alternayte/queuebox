# Phase 5 — Open source governance: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close finding F-058 to F-071. Give the repository the governance that a mainstream open
source project has.

**Architecture:** No product code changes. The work is continuous integration, a release path, the
community documents, the build hygiene, and the coverage exclusions.

**Tech Stack:** GitHub Actions, Gradle, ktlint, detekt, Docker buildx, GHCR.

**Spec:** `hardening-doc.md`, section 8.

## Already closed before this phase

| ID | Where | Note |
|----|-------|------|
| F-058 | Phase 1, F-012 | `LICENSE` exists and the README links to it. |
| F-062 | Phase 3, F-042 | The toolchain and both images target Java 21 LTS. This phase adds the CI matrix and the README range. |
| F-069 | Phase 2 | The gates are 80 percent line, 70 percent branch, 60 percent per module. |
| F-071 | Phase 2 | The template files and the `utils` module are gone. |

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed. Decision 2: container image only. No `maven-publish`, no
  signing. Decision 5: preserve no Task Master planning content.
- Write the test first where a test is possible. A workflow file has no unit test, so the
  evidence is the local equivalent command plus a review of the file.
- `./gradlew check` must pass after every finding.

## File structure

| File | Responsibility |
|------|----------------|
| `.github/workflows/ci.yml` | The build, test, lint and docker jobs, with the database matrix. |
| `.github/workflows/release.yml` | The tagged release that pushes the image to GHCR. |
| `.github/ISSUE_TEMPLATE/*`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS` | The templates. |
| `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md` | The community documents. |
| `docs/development/releasing.md`, `docs/development/building.md` | The maintainer documents. |
| `.editorconfig`, `config/detekt/detekt.yml` | The code style. |
| `gradle/libs.versions.toml` | Every dependency coordinate. |
| `gradle/verification-metadata.xml` or the lock files | The reproducible build. |

---

### Task 1: F-060, F-061 and F-064 — The community documents

**Files:** Create `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`,
`.github/CODEOWNERS`, `.github/PULL_REQUEST_TEMPLATE.md`,
`.github/ISSUE_TEMPLATE/bug_report.yml`, `.github/ISSUE_TEMPLATE/feature_request.yml`,
`.github/ISSUE_TEMPLATE/config.yml`, `docs/development/releasing.md`.

- [ ] **Step 1:** Write `SECURITY.md` with a supported versions table, a private reporting
      channel, and a response time commitment.
- [ ] **Step 2:** Write `CONTRIBUTING.md` with the prerequisites, the exact build and test
      commands, the code style rules, the commit message convention, the branch strategy, how to
      run only the fast tests, and how to add a migration.
- [ ] **Step 3:** Write `CODE_OF_CONDUCT.md` from the Contributor Covenant 2.1.
- [ ] **Step 4:** Write `CHANGELOG.md` in the Keep a Changelog form, with the 0.1.0 entry that
      names what Phases 1 to 5 changed.
- [ ] **Step 5:** Write `docs/development/releasing.md`: the tag, the workflow, the version bump,
      semantic versioning, and the compatibility policy for the configuration schema and the
      database schema.
- [ ] **Step 6:** Add the templates and `CODEOWNERS`.
- [ ] **Step 7:** Verify `CONTRIBUTING.md` literally, in a clean container, and record the
      transcript in the document or in `docs/build/STATUS.md`.

---

### Task 2: F-059, F-062 and F-063 — Continuous integration and the release

**Files:** Create `.github/workflows/ci.yml`, `.github/workflows/release.yml`,
`docs/development/building.md`. Modify `build.gradle.kts`, `README.md`.

- [ ] **Step 1:** Write `ci.yml` with the `build`, `test`, `lint` and `docker` jobs. Matrix the
      test job across PostgreSQL 14, 15 and 16, and SQL Server 2019 and 2022.
- [ ] **Step 2:** Make the database image configurable from the environment, so the matrix has
      something to vary. The test bases currently hard-code the image tags.
- [ ] **Step 3:** Derive `version` in the root build file from the Git tag, with a
      `0.0.0-SNAPSHOT` fallback.
- [ ] **Step 4:** Write `release.yml`, triggered on a `v*` tag. Build with buildx for
      `linux/amd64` and `linux/arm64`, push to GHCR with the exact version, the minor version and
      `latest`, and attach the software bill of materials and the provenance attestation.
- [ ] **Step 5:** Rewrite the README Quick Start to pull the published image. Move the local build
      instructions to `docs/development/building.md`.
- [ ] **Step 6:** Add the CI badge to the README. State the supported Java range.
- [ ] **Step 7:** Confirm `grep -rn "maven-publish" --include='*.gradle.kts' .` returns nothing.

---

### Task 3: F-065 and F-070 — Remove the agent tooling and tighten the exclusions

**Files:** Modify `.gitignore`, `build.gradle.kts`,
`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, `TESTING.md`. Remove paths from the index.

- [ ] **Step 1:** `git rm -r --cached .taskmaster .cursor .claude .mcp.json CLAUDE.md`. Delete no
      local file.
- [ ] **Step 2:** Add each path to `.gitignore`.
- [ ] **Step 3:** Confirm
      `git ls-files | grep -E '^\.(taskmaster|cursor|claude)/|^\.mcp\.json$'` returns nothing.
- [ ] **Step 4:** Replace the `**/*Table.class` and `**/*Tables.class` patterns with an explicit
      class list. `DynamicTables` carries the column mapping feature and must be measured.
- [ ] **Step 5:** Run `./gradlew check`. Add tests until the gates pass with the restored classes.
- [ ] **Step 6:** Name every excluded class and its reason in `TESTING.md`.

---

### Task 4: F-066, F-067 and F-068 — Build hygiene

This task rewrites many files, so it runs alone, after every other task.

**Files:** Modify every `*.gradle.kts`, `gradle/libs.versions.toml`. Create `.editorconfig`,
`config/detekt/detekt.yml`. Possibly every `*.kt` file, in one dedicated commit.

- [x] **Step 1:** Move every inline `group:artifact:version` string into the version catalog.
      Confirm with a grep that none remains.
- [x] **Step 2:** Add ktlint and detekt with a checked-in configuration, and an `.editorconfig`.
      Wire both into `check`.
- [x] **Step 3:** Run the formatter over the whole codebase in one dedicated commit, so the diff
      stays reviewable.
- [x] **Step 4:** Enable Gradle dependency verification or dependency locking, and commit the
      files.
- [x] **Step 5:** Prove the verification works: tamper with one dependency and record that the
      build fails.
- [x] **Step 6:** Run `./gradlew ktlintCheck detekt check`.

---

## Phase exit condition

`LICENSE`, CI, the templates and the release process are in place. A new contributor can go from
clone to a green build using only `CONTRIBUTING.md`, proved in a clean container.
