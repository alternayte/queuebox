# QueueBox Testing - Product Requirements Document

---

<overview>

## Problem Statement

QueueBox has completed its MVP implementation but lacks comprehensive test coverage. Without proper testing:
- Regressions can be introduced during future development
- Critical paths (message delivery, deduplication) are unverified
- Integration between modules is untested
- Docker deployment reliability is unknown

## Target Users

**Primary: QueueBox Developers** who need:
- Confidence that code changes don't break existing functionality
- Clear test examples for new feature development
- Fast feedback loop during development

## Solution

Implement a comprehensive test suite following the test pyramid:
- 60% Unit tests (pure functions, state machines, routing logic)
- 30% Integration tests (module interactions, database, HTTP clients)
- 10% E2E tests (full flows: outbox→destination, webhook→inbox)

## Success Metrics

1. **Line Coverage**: 80% minimum across all modules
2. **Branch Coverage**: 75% minimum
3. **Critical Path Coverage**: 95% for message delivery and deduplication
4. **Test Execution**: All tests pass in CI/CD pipeline
5. **Build Verification**: `./gradlew test` completes successfully

</overview>

---

<functional-decomposition>

## Capability Tree

### Capability: Test Infrastructure Setup
Foundation for running all tests.

#### Feature: Gradle Test Configuration
- **Description**: Configure Gradle for Kotlin test execution with JUnit 5
- **Inputs**: build.gradle.kts files for each module
- **Outputs**: Working test execution with `./gradlew test`
- **Behavior**: Include kotlin-test, JUnit 5, kotlinx-coroutines-test dependencies

#### Feature: TestContainers Integration
- **Description**: Set up TestContainers for PostgreSQL and RabbitMQ integration tests
- **Inputs**: Test dependencies, Docker availability
- **Outputs**: Auto-provisioned containers for integration tests
- **Behavior**: Start containers before tests, provide connection details, cleanup after

#### Feature: Ktor Test Client Setup
- **Description**: Configure Ktor test client for HTTP endpoint testing
- **Inputs**: Ktor test dependencies
- **Outputs**: In-memory test server capability
- **Behavior**: Run routes without network, provide test client

---

### Capability: Core Module Tests
Unit tests for domain models and interfaces.

#### Feature: MessageState Tests
- **Description**: Test state machine transitions
- **Inputs**: State enum values, transition events
- **Outputs**: Verification of valid/invalid transitions
- **Test Cases**:
  - pending → processing (valid)
  - processing → sent (valid)
  - processing → failed (valid)
  - sent → pending (invalid)
  - failed → pending for retry (valid)
  - failed → dead after max attempts (valid)

#### Feature: OutboxMessage Tests
- **Description**: Test outbox message data class
- **Inputs**: Message creation parameters
- **Outputs**: Correctly initialized message objects
- **Test Cases**:
  - UUID generated on creation
  - Default state is pending
  - Attempt counter starts at 0
  - Timestamps set correctly

#### Feature: InboxMessage Tests
- **Description**: Test inbox message data class
- **Inputs**: Message creation parameters
- **Outputs**: Correctly initialized message objects
- **Test Cases**:
  - Source + idempotency_key form unique identifier
  - Default state is pending
  - Payload stored correctly

#### Feature: Destination Tests
- **Description**: Test sealed class hierarchy for destinations
- **Inputs**: Destination configurations
- **Outputs**: Correct type discrimination
- **Test Cases**:
  - HTTP destination parsing
  - RabbitMQ destination parsing
  - Type-safe pattern matching

---

### Capability: Config Module Tests
Unit tests for configuration loading and validation.

#### Feature: ConfigLoader Tests
- **Description**: Test YAML loading and environment variable substitution
- **Inputs**: YAML strings, environment variables
- **Outputs**: Parsed configuration or errors
- **Test Cases**:
  - Load valid YAML configuration
  - Substitute ${VAR} patterns from environment
  - Throw on missing required environment variable
  - Handle optional environment variables with defaults
  - Parse all configuration sections correctly

#### Feature: ConfigValidator Tests
- **Description**: Test configuration validation rules
- **Inputs**: Parsed configuration objects
- **Outputs**: Validation pass/fail with messages
- **Test Cases**:
  - Valid configuration passes
  - Missing required fields fails
  - Invalid URL format fails
  - Invalid port number fails
  - Route referencing non-existent destination fails
  - Duplicate destination names fails

---

### Capability: PostgreSQL Module Tests
Integration tests with TestContainers PostgreSQL.

#### Feature: DatabaseFactory Tests
- **Description**: Test HikariCP connection pool setup
- **Inputs**: Database configuration
- **Outputs**: Working DataSource
- **Test Cases**:
  - Connection pool initializes correctly
  - Connections are reused
  - Pool exhaustion handling

#### Feature: OutboxRepository Tests
- **Description**: Test outbox CRUD and claiming operations
- **Inputs**: OutboxMessages, batch sizes
- **Outputs**: Correct database state
- **Test Cases**:
  - Insert message creates pending record
  - claimBatch returns up to N pending messages
  - claimBatch uses FOR UPDATE SKIP LOCKED
  - Concurrent claims don't return duplicates
  - markSent updates state correctly
  - markFailed increments attempt counter
  - scheduleRetry sets scheduled_at
  - markDead sets dead state
  - Empty table returns empty list

#### Feature: InboxRepository Tests
- **Description**: Test inbox storage with deduplication
- **Inputs**: InboxMessages with idempotency keys
- **Outputs**: Storage results
- **Test Cases**:
  - First message stores successfully
  - Duplicate key returns duplicate indicator
  - Different source + same key = different message
  - Unique constraint on (source, idempotency_key)
  - claimPending returns pending messages

---

### Capability: Outbox Service Tests
Unit and integration tests for outbox processing.

#### Feature: RetryStrategy Tests
- **Description**: Test exponential backoff calculations
- **Inputs**: Attempt count, base delay, max attempts
- **Outputs**: Calculated delays
- **Test Cases**:
  - First attempt delay = base delay
  - Second attempt delay = base * 2
  - Third attempt delay = base * 4
  - Max delay caps exponential growth
  - shouldRetry returns false after max attempts

#### Feature: MessageRouter Tests
- **Description**: Test topic-based routing
- **Inputs**: Message topics, route configurations
- **Outputs**: Matched destinations
- **Test Cases**:
  - Exact match: "order.created" matches "order.created"
  - Glob match: "order.created" matches "order.*"
  - Wildcard match: "anything" matches "*"
  - First matching route wins
  - No matching route returns null
  - Multiple routes evaluated in order

#### Feature: HttpPublisher Tests
- **Description**: Test HTTP message delivery (with mock server)
- **Inputs**: OutboxMessages, HTTP destination config
- **Outputs**: Success/failure results
- **Test Cases**:
  - POST to valid endpoint returns success
  - Correct headers included (Content-Type, X-Topic, X-Message-Id, X-Attempt)
  - Timeout after configured duration returns failure
  - 5xx response returns failure
  - Connection refused returns failure

#### Feature: OutboxPoller Integration Tests
- **Description**: Test full polling flow
- **Inputs**: Messages in database, mock destinations
- **Outputs**: Message state changes
- **Test Cases**:
  - Poll → claim → route → publish → markSent
  - Failed publish triggers retry scheduling
  - Max attempts reached triggers markDead
  - Graceful shutdown completes in-flight

---

### Capability: Inbox Service Tests
Unit and integration tests for inbox receiving.

#### Feature: IdempotencyExtractor Tests
- **Description**: Test JSONPath key extraction
- **Inputs**: JSON payloads, JSONPath expressions
- **Outputs**: Extracted keys or errors
- **Test Cases**:
  - Simple path: $.id extracts root id field
  - Nested path: $.data.transaction_id extracts nested field
  - Array index: $.items[0].id extracts from array
  - Missing field returns error
  - Invalid JSONPath returns error

#### Feature: InboxHandler Tests
- **Description**: Test inbox coordination logic
- **Inputs**: Incoming webhooks
- **Outputs**: Storage results
- **Test Cases**:
  - Valid webhook stores successfully
  - Duplicate webhook detected and reported
  - Missing idempotency key returns error
  - Invalid JSON returns error

#### Feature: InboxRoutes Integration Tests
- **Description**: Test Ktor HTTP endpoints
- **Inputs**: HTTP requests
- **Outputs**: HTTP responses
- **Test Cases**:
  - POST to configured source returns 200
  - Duplicate returns 200 (or 409 based on design)
  - Invalid payload returns 400
  - Unknown source returns 404
  - Correct Content-Type required

---

### Capability: RabbitMQ Module Tests
Integration tests with TestContainers RabbitMQ.

#### Feature: RabbitConnection Tests
- **Description**: Test connection management
- **Inputs**: RabbitMQ configuration
- **Outputs**: Active connection
- **Test Cases**:
  - Connects on startup
  - Auto-reconnects after disconnect
  - Exponential backoff on connection failures

#### Feature: RabbitPublisher Tests
- **Description**: Test RabbitMQ message publishing
- **Inputs**: OutboxMessages, exchange/routing config
- **Outputs**: Publish confirmations
- **Test Cases**:
  - Publishes to configured exchange
  - Routing key templating works
  - Confirms received for successful publish
  - Handles publish failures gracefully

#### Feature: RabbitConsumer Tests
- **Description**: Test queue consumption
- **Inputs**: Messages on RabbitMQ queue
- **Outputs**: Messages stored in inbox
- **Test Cases**:
  - Consumes from configured queue
  - Extracts idempotency key
  - Stores in inbox repository
  - Acks after successful store
  - Nacks on storage failure

---

### Capability: Application Tests
Integration and E2E tests for full application.

#### Feature: Health Check Tests
- **Description**: Test health endpoint
- **Inputs**: HTTP GET /health
- **Outputs**: Health status JSON
- **Test Cases**:
  - Returns 200 when all components healthy
  - Returns 503 when database unavailable
  - Includes component status breakdown

#### Feature: Metrics Tests
- **Description**: Test metrics endpoint
- **Inputs**: HTTP GET /metrics
- **Outputs**: Prometheus format metrics
- **Test Cases**:
  - Returns correct format
  - Includes message counters
  - Counters increment correctly

#### Feature: E2E Outbox Flow Tests
- **Description**: Test complete outbox-to-destination flow
- **Inputs**: Message in outbox table
- **Outputs**: Message delivered to HTTP/RabbitMQ destination
- **Test Cases**:
  - HTTP: outbox → polled → POST to endpoint → marked sent
  - RabbitMQ: outbox → polled → published to exchange → marked sent

#### Feature: E2E Inbox Flow Tests
- **Description**: Test complete webhook-to-inbox flow
- **Inputs**: HTTP webhook / RabbitMQ message
- **Outputs**: Message stored in inbox table
- **Test Cases**:
  - HTTP: POST webhook → deduplicated → stored in inbox
  - RabbitMQ: consumed → deduplicated → stored in inbox → acked

</functional-decomposition>

---

<structural-decomposition>

## Test File Structure

```
queuebox/
├── core/
│   └── src/test/kotlin/
│       ├── MessageStateTest.kt
│       ├── OutboxMessageTest.kt
│       ├── InboxMessageTest.kt
│       └── DestinationTest.kt
├── config/
│   └── src/test/kotlin/
│       ├── ConfigLoaderTest.kt
│       └── ConfigValidatorTest.kt
├── postgres/
│   └── src/test/kotlin/
│       ├── DatabaseFactoryTest.kt
│       ├── OutboxRepositoryTest.kt
│       └── InboxRepositoryTest.kt
├── outbox-service/
│   └── src/test/kotlin/
│       ├── RetryStrategyTest.kt
│       ├── MessageRouterTest.kt
│       ├── HttpPublisherTest.kt
│       └── OutboxPollerTest.kt
├── inbox-service/
│   └── src/test/kotlin/
│       ├── IdempotencyExtractorTest.kt
│       ├── InboxHandlerTest.kt
│       └── InboxRoutesTest.kt
├── rabbitmq/
│   └── src/test/kotlin/
│       ├── RabbitConnectionTest.kt
│       ├── RabbitPublisherTest.kt
│       └── RabbitConsumerTest.kt
└── app/
    └── src/test/kotlin/
        ├── HealthCheckTest.kt
        ├── MetricsTest.kt
        ├── E2EOutboxFlowTest.kt
        └── E2EInboxFlowTest.kt
```

</structural-decomposition>

---

<dependency-graph>

## Test Dependencies

### Phase 1: Test Infrastructure
No dependencies - set up first.

- **Gradle test configuration**: Add test dependencies to all modules
- **TestContainers setup**: Add testcontainers-postgresql, testcontainers-rabbitmq

### Phase 2: Unit Tests
Depends on test infrastructure.

- **Core module tests**: No external dependencies (pure unit tests)
- **Config module tests**: No external dependencies
- **RetryStrategy tests**: No external dependencies
- **MessageRouter tests**: No external dependencies
- **IdempotencyExtractor tests**: No external dependencies

### Phase 3: Integration Tests
Depends on unit tests.

- **PostgreSQL tests**: Depends on TestContainers
- **HttpPublisher tests**: Depends on mock server (Ktor test or MockK)
- **RabbitMQ tests**: Depends on TestContainers

### Phase 4: E2E Tests
Depends on integration tests.

- **E2E flow tests**: Depends on all integration tests working

</dependency-graph>

---

<implementation-roadmap>

## Development Phases

### Phase 1: Test Infrastructure
**Goal**: Set up test dependencies and infrastructure.

**Tasks**:
- [ ] Add test dependencies to root build.gradle.kts (JUnit 5, kotlin-test, kotlinx-coroutines-test)
- [ ] Add TestContainers dependencies for PostgreSQL and RabbitMQ
- [ ] Add Ktor test client dependency
- [ ] Add MockK for mocking
- [ ] Create shared test utilities (TestContainers setup helpers)

### Phase 2: Core & Config Unit Tests
**Goal**: Test pure domain logic.

**Tasks**:
- [ ] Implement MessageState transition tests
- [ ] Implement OutboxMessage tests
- [ ] Implement InboxMessage tests
- [ ] Implement Destination sealed class tests
- [ ] Implement ConfigLoader tests
- [ ] Implement ConfigValidator tests

### Phase 3: Service Unit Tests
**Goal**: Test service layer logic with mocks.

**Tasks**:
- [ ] Implement RetryStrategy tests
- [ ] Implement MessageRouter tests
- [ ] Implement IdempotencyExtractor tests
- [ ] Implement InboxHandler tests with mocked repository

### Phase 4: PostgreSQL Integration Tests
**Goal**: Test database operations with real PostgreSQL.

**Tasks**:
- [ ] Implement DatabaseFactory tests
- [ ] Implement OutboxRepository tests (including concurrent claiming)
- [ ] Implement InboxRepository tests (including deduplication)

### Phase 5: HTTP & RabbitMQ Integration Tests
**Goal**: Test external integrations.

**Tasks**:
- [ ] Implement HttpPublisher tests with mock server
- [ ] Implement InboxRoutes tests with Ktor test client
- [ ] Implement RabbitConnection tests
- [ ] Implement RabbitPublisher tests
- [ ] Implement RabbitConsumer tests

### Phase 6: E2E & Application Tests
**Goal**: Test complete flows.

**Tasks**:
- [ ] Implement Health check endpoint tests
- [ ] Implement Metrics endpoint tests
- [ ] Implement E2E outbox-to-HTTP flow test
- [ ] Implement E2E outbox-to-RabbitMQ flow test
- [ ] Implement E2E webhook-to-inbox flow test
- [ ] Implement E2E RabbitMQ-to-inbox flow test

</implementation-roadmap>

---

<test-conventions>

## Test Naming Convention

Use: `should_expectedBehavior_when_condition`

Examples:
- `should_returnPendingMessages_when_batchClaimed`
- `should_detectDuplicate_when_sameIdempotencyKey`
- `should_calculateExponentialDelay_when_retrying`

## Test Structure

Use Arrange-Act-Assert pattern:

```kotlin
@Test
fun should_returnEmpty_when_noMessagesInOutbox() {
    // Arrange
    val repository = OutboxRepository(dataSource)

    // Act
    val result = repository.claimBatch(10)

    // Assert
    assertThat(result).isEmpty()
}
```

## Coroutine Testing

Use `runTest` from kotlinx-coroutines-test:

```kotlin
@Test
fun should_pollAndPublish_when_messagesAvailable() = runTest {
    // Arrange
    val poller = OutboxPoller(repository, router, publisher)

    // Act
    poller.pollOnce()

    // Assert
    verify { publisher.publish(any()) }
}
```

</test-conventions>
