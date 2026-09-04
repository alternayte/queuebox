# Phase 3 — Security hardening: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close finding F-034 to F-045. Remove the security holes and the credential leaks.

**Architecture:** A `Secret` value class carries every credential, so no `toString` prints one.
The same type resolves a `file:` reference at load time. The admin endpoint gains authentication,
a clamp, and a default of disabled. The inbox authentication validator signs the timestamp, parses
the bearer scheme, and uses `MessageDigest.isEqual`. The HTTP publisher truncates and redacts an
error body, and it validates every destination URL.

**Tech Stack:** Kotlin, Gradle, Hoplite, Ktor, coroutines, Testcontainers, JUnit 5, JaCoCo,
CycloneDX, Trivy, GitHub Actions.

**Spec:** `hardening-doc.md`, section 6.

## Global constraints

- Do not redesign the product. Do not add a feature that `hardening-doc.md` does not list.
- Section 2A decisions are closed.
- Write the test first. Confirm the failure. Then write the fix.
- Every finding closes with command output as evidence.
- `./gradlew check` must pass after every finding, with the Phase 2 coverage gates in force:
  80 percent aggregate line, 70 percent aggregate branch, 60 percent per module.

## File structure

| File | Responsibility |
|------|----------------|
| `core/src/main/kotlin/Secret.kt` | The value class that masks a credential and resolves `file:`. |
| `config/src/main/kotlin/ConfigLoader.kt` | Registers the Hoplite decoder for `Secret`. |
| `config/src/main/kotlin/AuthConfig.kt`, `QueueBoxConfig.kt` | Credential fields become `Secret`. |
| `core/src/main/kotlin/DestinationAuthConfig.kt` | The same. |
| `inbox-service/src/main/kotlin/auth/InboxAuthValidator.kt` | Scheme parsing, signed timestamp, `MessageDigest.isEqual`. |
| `app/src/main/kotlin/AdminRoutes.kt` | Authentication, the clamp, and the enabled flag. |
| `outbox-service/src/main/kotlin/http/HttpPublisher.kt` | Bounded, redacted error body. URL builder. |
| `config/src/main/kotlin/ConfigValidator.kt` | Destination URL validation. |
| `Dockerfile`, `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` | Java 21 LTS. |
| `.github/workflows/security.yml`, `.github/dependabot.yml` | The security job and the updates. |
| `docs/operations/security.md` | Transport security and the secret manager patterns. |

---

### Task 1: F-038 and F-045 — The `Secret` value class and the `file:` reference

**Files:** Create `core/src/main/kotlin/Secret.kt`. Modify `core/src/main/kotlin/DestinationAuthConfig.kt`,
`config/src/main/kotlin/AuthConfig.kt`, `config/src/main/kotlin/QueueBoxConfig.kt`,
`config/src/main/kotlin/ConfigLoader.kt`, and every call site that reads a credential.
Test: `core/src/test/kotlin/org/nxtspec/SecretTest.kt`,
`config/src/test/kotlin/org/nxtspec/ConfigSecretTest.kt`.

**Interfaces produced:**
- `value class Secret(private val raw: String)` with `fun reveal(): String` and a masked `toString`.
- `Secret.of(value: String): Secret` resolves a `file:` prefix by reading the file at load time.
- A Hoplite decoder and a kotlinx serializer, so both loading paths accept a plain string.

- [ ] **Step 1:** Write `SecretTest`. Assert `toString` masks the value, that `reveal` returns it,
      that `equals` still works, and that a `file:` reference reads the file and trims the trailing
      newline. Assert a missing file fails with a message that names the path but not the content.
- [ ] **Step 2:** Write `ConfigSecretTest`. Load a configuration that sets every credential field,
      then assert `config.toString()` contains none of the values. Enumerate the fields explicitly:
      `database.password`, `sources.*.auth.token`, `sources.*.auth.key`, `sources.*.auth.secret`,
      `destinations.*.auth.clientSecret`, `destinations.*.auth.password`,
      `destinations.*.auth.headerValue`.
- [ ] **Step 3:** Run both. Expected: FAIL, no `Secret` type exists.
- [ ] **Step 4:** Implement `Secret`, the decoder, and the serializer. Change every credential
      field. Update every call site to `reveal()`.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

**DoD:** `config.toString()` contains no configured secret value, and a `file:` reference resolves.

---

### Task 2: F-036, F-037 and F-035 — The inbox authentication validator

**Files:** Modify `inbox-service/src/main/kotlin/auth/InboxAuthValidator.kt`,
`config/src/main/kotlin/AuthConfig.kt`.
Test: `inbox-service/src/test/kotlin/org/nxtspec/auth/InboxAuthValidatorTest.kt`.

**Interfaces produced:** `InboxAuthConfig.HmacSignature.signaturePayloadFormat`, an enum of
`BODY` and `TIMESTAMP_DOT_BODY`, defaulting to `TIMESTAMP_DOT_BODY` when `timestampHeader` is set.

- [ ] **Step 1:** Write table-driven bearer tests for `Bearer x`, `bearer x`, `x`, `Basic x`, and
      an empty header. Write a test that replays a captured request with an updated timestamp and
      asserts 401. Write tests that `secureCompare` still returns the same answers.
- [ ] **Step 2:** Run them. Expected: FAIL on the bare token, on the lowercase scheme, and on the
      replay.
- [ ] **Step 3:** Parse the Authorization header into scheme and credentials. Compare the scheme
      case insensitively.
- [ ] **Step 4:** Sign `timestamp + "." + body` when the format asks for it.
- [ ] **Step 5:** Replace the hand-rolled comparison with `MessageDigest.isEqual` over the SHA-256
      digests of both values, which removes the length leak.
- [ ] **Step 6:** Run the tests, then `./gradlew check`.

---

### Task 3: F-034 — Authenticate and clamp the admin endpoint

**Files:** Modify `app/src/main/kotlin/AdminRoutes.kt`, `app/src/main/kotlin/App.kt`,
`config/src/main/kotlin/QueueBoxConfig.kt`.
Test: `app/src/test/kotlin/AdminRoutesTest.kt`, `app/src/test/kotlin/AdminGuardTest.kt`.

**Interfaces produced:** `admin.enabled` default false, `admin.insecure` default false,
`admin.auth` of type `InboxAuthConfig?`, `admin.maxTransformTimeoutMs` default 1000,
`admin.maxPayloadBytes` default 65536. `requireAdminAuth(config)` throws when admin routes are
enabled with no authentication and `insecure` is false.

- [ ] **Step 1:** Write tests: 401 with no credentials, success with credentials, a request asking
      for `timeoutMs = 600000` is clamped to `maxTransformTimeoutMs`, and the application refuses
      to start with `enabled = true`, no `auth`, and `insecure = false`.
- [ ] **Step 2:** Run them. Expected: FAIL, the endpoint is open and the timeout is unbounded.
- [ ] **Step 3:** Register the route only when `admin.enabled`. Validate with `InboxAuthValidator`.
      Clamp the timeout and the payload size.
- [ ] **Step 4:** Run the tests, then `./gradlew check`.

---

### Task 4: F-039 and F-040 — The HTTP publisher and the destination URL

**Files:** Modify `outbox-service/src/main/kotlin/http/HttpPublisher.kt`,
`config/src/main/kotlin/ConfigValidator.kt`, `config/src/main/kotlin/QueueBoxConfig.kt`.
Test: `outbox-service/src/test/kotlin/org/nxtspec/HttpPublisherTest.kt`,
`config/src/test/kotlin/org/nxtspec/ConfigValidatorTest.kt`.

**Interfaces produced:** `http.maxErrorBodyBytes` default 2048,
`http.blockPrivateAddresses` default false.

- [ ] **Step 1:** Write a test with a 1 MB error body that asserts the exception message is at
      most the configured size. Write validator tests for a missing scheme, a `file://` scheme, a
      double slash join, and, with the flag on, `http://169.254.169.254/`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Truncate and redact the error body inside the publisher. Join the base URL and
      the path with `URLBuilder` rather than string concatenation.
- [ ] **Step 4:** Validate every destination URL at startup.
- [ ] **Step 5:** Run the tests, then `./gradlew check`.

---

### Task 5: F-042 — Java 21 LTS

**Files:** Modify `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, `Dockerfile`.

- [ ] **Step 1:** Change `jvmToolchain(23)` to `jvmToolchain(21)`.
- [ ] **Step 2:** Change the builder image to a JDK 21 image and the runtime image to
      `eclipse-temurin:21-jre-alpine`.
- [ ] **Step 3:** Run `./gradlew clean build check`.
- [ ] **Step 4:** Run `docker build -t queuebox:test .` and then
      `docker run --rm --entrypoint java queuebox:test -version`. Confirm it reports Java 21.

---

### Task 6: F-043 and F-044 — Dependency and image security in CI

**Files:** Create `.github/workflows/security.yml`, `.github/dependabot.yml`,
`gradle/verification-suppressions.xml`. Modify `gradle/libs.versions.toml`,
`build.gradle.kts`, `Dockerfile`.

- [ ] **Step 1:** Add the CycloneDX Gradle plugin. Run `./gradlew cyclonedxBom` and confirm it
      writes an SBOM.
- [ ] **Step 2:** Add a `security` job to `.github/workflows/security.yml` that runs the
      dependency scan, the SBOM, and a Trivy image scan, and that fails on a high or critical
      finding. Add the documented suppression file.
- [ ] **Step 3:** Add `.github/dependabot.yml` for Gradle and for GitHub Actions.
- [ ] **Step 4:** Pin every base image in the `Dockerfile` by digest. Add the buildx provenance
      flag to the documented build command.

---

### Task 7: F-041 and F-045 documentation — Transport security and secret managers

**Files:** Create `docs/operations/security.md`. Modify `README.md`.

- [ ] **Step 1:** Write a "Transport security" section with a working ingress example that
      terminates TLS in front of QueueBox.
- [ ] **Step 2:** Write a "Secrets" section covering the `file:` prefix, the Kubernetes secret
      pattern, and the rule that a secret never appears in a log line.
- [ ] **Step 3:** Link both from the README.

---

## Phase exit condition

All security findings closed. `/admin` is authenticated. Request size limits enforced. Secrets
never printed. `./gradlew clean build check jacocoAggregatedReport` passes.
