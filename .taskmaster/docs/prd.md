# QueueBox - Product Requirements Document (RPG Format)

---

<overview>

## Problem Statement

Applications face two critical reliability challenges when integrating with message brokers:

**Outbox Problem:** Applications need to save data AND publish events atomically. Without the outbox pattern, you get "database saved but event lost" or "event sent but database rolled back" bugs. This leads to data inconsistency between services.

**Inbox Problem:** Applications receive webhooks/messages that may be delivered multiple times. Without the inbox pattern, you get duplicate charges, double emails, and other idempotency failures.

## Target Users

**Primary: Backend Engineers** building event-driven microservices who need:
- Reliable event publishing without distributed transactions
- Webhook deduplication without custom infrastructure
- Language-agnostic solution (writes to database, not SDK)

**Secondary: DevOps/Platform Teams** who want:
- Simple sidecar deployment model
- Minimal operational overhead
- Observable message flow

## Solution

QueueBox runs as a sidecar alongside any application. The app writes to an outbox table in its own transaction, and QueueBox reliably delivers those messages. For incoming webhooks, QueueBox receives them, deduplicates by idempotency key, and stores in an inbox table for the app to process.

## Success Metrics

1. **Reliability**: Zero message loss under normal operation (at-least-once delivery)
2. **Deduplication**: 100% duplicate detection via idempotency keys
3. **Latency**: Sub-100ms for inbox HTTP receiver responses
4. **Throughput**: Handle 1000+ messages/second
5. **Simplicity**: Single Docker container deployment, YAML configuration

</overview>

---

<functional-decomposition>

## Capability Tree

### Capability: Configuration Management
Handles loading, parsing, and validating application configuration from YAML files with environment variable support.

#### Feature: YAML Configuration Loading
- **Description**: Load configuration from YAML files with support for multiple sources
- **Inputs**: File path or classpath resource path
- **Outputs**: Parsed configuration object or validation errors
- **Behavior**: Read file, parse YAML using kotlinx.serialization, return typed config object

#### Feature: Environment Variable Substitution
- **Description**: Replace `${VAR_NAME}` placeholders with environment variable values
- **Inputs**: Raw configuration string, environment variables map
- **Outputs**: Interpolated configuration string
- **Behavior**: Regex match `${...}` patterns, lookup in environment, substitute or throw if required and missing

#### Feature: Configuration Validation
- **Description**: Validate configuration values meet requirements on startup
- **Inputs**: Parsed configuration object
- **Outputs**: Validation result (pass/fail) with error messages
- **Behavior**: Check required fields present, validate URLs/ports/timeouts are valid, ensure destinations referenced in routes exist

---

### Capability: Core Domain Models
Foundational data structures and interfaces shared across all modules.

#### Feature: Message State Machine
- **Description**: Define valid states and transitions for outbox/inbox messages
- **Inputs**: Current state, transition event
- **Outputs**: New state or transition error
- **Behavior**: Enforce valid transitions: pending→processing→sent/failed, failed→pending (retry) or dead

#### Feature: Outbox Message Model
- **Description**: Data class representing an outbox message with all persistence fields
- **Inputs**: Topic, key (optional), payload, scheduling options
- **Outputs**: Immutable message object with UUID, timestamps, state tracking
- **Behavior**: Generate UUID on creation, default state to pending, track attempts

#### Feature: Inbox Message Model
- **Description**: Data class representing an inbox message with deduplication fields
- **Inputs**: Source, idempotency key, event type, payload
- **Outputs**: Immutable message object with unique constraint fields
- **Behavior**: Enforce source+idempotency_key uniqueness, track processing state

#### Feature: Destination Abstractions
- **Description**: Sealed interface hierarchy for different destination types
- **Inputs**: Destination configuration from YAML
- **Outputs**: Typed destination objects (HTTP, RabbitMQ)
- **Behavior**: Parse destination config into appropriate sealed class variant

#### Feature: Publisher Interface
- **Description**: Common interface for all message publishers
- **Inputs**: OutboxMessage to publish
- **Outputs**: Result<Unit> indicating success or failure with error details
- **Behavior**: Abstract publish operation, implementations handle protocol specifics

---

### Capability: Database Operations
PostgreSQL repository layer for transactional message storage and retrieval.

#### Feature: Connection Pool Management
- **Description**: Manage database connection pool lifecycle with HikariCP
- **Inputs**: Database configuration (URL, credentials, pool size)
- **Outputs**: Configured DataSource, health status
- **Behavior**: Initialize pool on startup, provide connections, handle pool exhaustion gracefully

#### Feature: Outbox Batch Claiming
- **Description**: Atomically claim a batch of pending messages for processing
- **Inputs**: Batch size limit
- **Outputs**: List of claimed OutboxMessages (now in 'processing' state)
- **Behavior**: Use `FOR UPDATE SKIP LOCKED` to claim without blocking other instances, update state to processing in same transaction

#### Feature: Outbox State Updates
- **Description**: Update message state after publish attempt
- **Inputs**: Message ID, new state, optional error message
- **Outputs**: Update confirmation
- **Behavior**: markSent, markFailed (increment attempt), scheduleRetry (set scheduled_at), markDead

#### Feature: Inbox Message Storage
- **Description**: Store incoming messages with deduplication
- **Inputs**: InboxMessage to store
- **Outputs**: Result indicating success, duplicate, or error
- **Behavior**: INSERT with ON CONFLICT DO NOTHING, return appropriate result based on affected rows

#### Feature: Inbox Batch Claiming
- **Description**: Claim pending inbox messages for application processing
- **Inputs**: Batch size limit
- **Outputs**: List of claimed InboxMessages
- **Behavior**: Similar to outbox claiming with FOR UPDATE SKIP LOCKED

---

### Capability: Outbox Processing
Polls outbox table and routes messages to configured destinations.

#### Feature: Polling Loop
- **Description**: Continuously poll for pending messages at configured interval
- **Inputs**: Poll interval, batch size from configuration
- **Outputs**: Triggered message processing for each batch
- **Behavior**: Coroutine-based loop with configurable interval, graceful shutdown support

#### Feature: Topic-Based Routing
- **Description**: Route messages to destinations based on topic pattern matching
- **Inputs**: Message topic, route configuration
- **Outputs**: Matched destination and routing parameters
- **Behavior**: Evaluate routes in order, support glob patterns (e.g., "order.*"), first match wins

#### Feature: Retry with Backoff
- **Description**: Handle failed publishes with exponential backoff retry
- **Inputs**: Failed message, attempt count, retry configuration
- **Outputs**: Scheduled retry or dead letter
- **Behavior**: Calculate delay = base_delay * 2^attempt, schedule retry or mark dead after max attempts

#### Feature: Graceful Shutdown
- **Description**: Complete in-flight processing before shutdown
- **Inputs**: SIGTERM signal
- **Outputs**: Clean shutdown confirmation
- **Behavior**: Stop accepting new batches, wait for current batch to complete, close resources

---

### Capability: HTTP Publishing
Delivers outbox messages to HTTP endpoints.

#### Feature: HTTP Client Management
- **Description**: Manage Ktor HTTP client with connection pooling and timeouts
- **Inputs**: HTTP destination configuration
- **Outputs**: Configured HTTP client instance
- **Behavior**: Create client with timeout settings, connection pooling, retry disabled (handled at outbox level)

#### Feature: Message Delivery
- **Description**: POST message payload to configured endpoint
- **Inputs**: OutboxMessage, HTTP destination config
- **Outputs**: Result indicating success (2xx) or failure
- **Behavior**: POST to base_url + path, include headers (Content-Type, X-Topic, X-Message-Id, X-Attempt), handle timeouts

---

### Capability: HTTP Inbox Receiver
Receives webhooks and stores with deduplication.

#### Feature: Webhook Endpoint
- **Description**: Ktor route handler for incoming webhooks
- **Inputs**: HTTP POST request with JSON payload
- **Outputs**: HTTP response (200 OK, 409 Conflict for duplicates)
- **Behavior**: Extract payload, route to appropriate source handler based on path

#### Feature: Idempotency Key Extraction
- **Description**: Extract idempotency key from payload using JSONPath
- **Inputs**: JSON payload, JSONPath expression from config
- **Outputs**: Extracted key string or error
- **Behavior**: Parse JSONPath, navigate payload, extract string value

#### Feature: Deduplication Storage
- **Description**: Store message with duplicate detection
- **Inputs**: Extracted message with idempotency key
- **Outputs**: Storage result (new, duplicate, error)
- **Behavior**: Delegate to InboxRepository, handle unique constraint violations gracefully

---

### Capability: Application Lifecycle
Main application wiring, health checks, and metrics.

#### Feature: Dependency Injection Setup
- **Description**: Wire all components together on startup
- **Inputs**: Configuration object
- **Outputs**: Fully initialized application context
- **Behavior**: Create repositories, services, publishers in dependency order

#### Feature: Health Check Endpoint
- **Description**: Expose application health for container orchestration
- **Inputs**: HTTP GET /health
- **Outputs**: Health status JSON with component states
- **Behavior**: Check database connectivity, return aggregated health

#### Feature: Metrics Endpoint
- **Description**: Expose basic operational metrics
- **Inputs**: HTTP GET /metrics
- **Outputs**: Prometheus-format metrics
- **Behavior**: Expose counters for messages processed, failed, inbox received

#### Feature: Server Lifecycle
- **Description**: Start Ktor server and manage graceful shutdown
- **Inputs**: Server configuration, SIGTERM signals
- **Outputs**: Running server, clean shutdown
- **Behavior**: Start on configured port, register shutdown hooks, coordinate service shutdown

---

### Capability: RabbitMQ Integration (Phase 2)
Publish to and consume from RabbitMQ.

#### Feature: Connection Management
- **Description**: Manage RabbitMQ connection with automatic reconnection
- **Inputs**: RabbitMQ connection URL
- **Outputs**: Active connection, reconnection on failure
- **Behavior**: Connect on startup, detect disconnection, exponential backoff reconnect

#### Feature: Exchange Declaration
- **Description**: Declare exchange on startup
- **Inputs**: Exchange name, type (direct/topic/fanout)
- **Outputs**: Declared exchange ready for publishing
- **Behavior**: Declare idempotently, handle already-exists gracefully

#### Feature: Message Publishing
- **Description**: Publish messages with routing key and confirms
- **Inputs**: OutboxMessage, routing key template
- **Outputs**: Publish result with confirmation
- **Behavior**: Render routing key template, publish with mandatory flag, wait for confirm

#### Feature: Queue Consumption
- **Description**: Consume messages from queue for inbox storage
- **Inputs**: Queue name, prefetch count
- **Outputs**: Stream of received messages
- **Behavior**: Consume with manual ack, extract idempotency key, store in inbox, ack after successful store

</functional-decomposition>

---

<structural-decomposition>

## Repository Structure

```
queuebox/
├── app/                          # Application Lifecycle capability
│   └── src/main/kotlin/
│       ├── Application.kt        # Main entry, DI setup
│       └── Server.kt             # Ktor server configuration
├── config/                       # Configuration Management capability
│   └── src/main/kotlin/
│       ├── ConfigLoader.kt       # YAML loading + env substitution
│       ├── ConfigValidator.kt    # Validation logic
│       └── model/                # Configuration data classes
│           ├── AppConfig.kt
│           ├── DatabaseConfig.kt
│           ├── OutboxConfig.kt
│           ├── DestinationConfig.kt
│           ├── RouteConfig.kt
│           └── InboxConfig.kt
├── core/                         # Core Domain Models capability
│   └── src/main/kotlin/
│       ├── model/
│       │   ├── OutboxMessage.kt
│       │   ├── InboxMessage.kt
│       │   ├── MessageState.kt
│       │   └── Destination.kt
│       └── api/
│           ├── Publisher.kt      # Publisher interface
│           └── InboxReceiver.kt  # Receiver interface
├── postgres/                     # Database Operations capability
│   └── src/main/kotlin/
│       ├── DatabaseFactory.kt    # HikariCP setup
│       ├── OutboxRepository.kt   # Outbox CRUD + claiming
│       └── InboxRepository.kt    # Inbox CRUD + dedup
├── outbox-service/               # Outbox Processing + HTTP Publishing
│   └── src/main/kotlin/
│       ├── OutboxPoller.kt       # Polling loop
│       ├── MessageRouter.kt      # Topic routing
│       ├── RetryStrategy.kt      # Backoff logic
│       └── http/
│           └── HttpPublisher.kt  # HTTP destination
├── inbox-service/                # HTTP Inbox Receiver capability
│   └── src/main/kotlin/
│       ├── InboxRoutes.kt        # Ktor routes
│       ├── IdempotencyExtractor.kt  # JSONPath extraction
│       └── InboxHandler.kt       # Coordination logic
├── rabbitmq/                     # RabbitMQ Integration capability
│   └── src/main/kotlin/
│       ├── RabbitConnection.kt   # Connection management
│       ├── RabbitPublisher.kt    # Publisher implementation
│       └── RabbitConsumer.kt     # Queue consumer
└── build.gradle.kts              # Multi-module build
```

## Module Definitions

### Module: core
- **Maps to capability**: Core Domain Models
- **Responsibility**: Shared domain types and interfaces with zero external dependencies
- **Exports**:
  - `OutboxMessage` - Outbox message data class
  - `InboxMessage` - Inbox message data class
  - `MessageState` - State enum/sealed class
  - `Destination` - Sealed interface for destination types
  - `Publisher` - Publisher interface
  - `InboxReceiver` - Receiver interface

### Module: config
- **Maps to capability**: Configuration Management
- **Responsibility**: Load, parse, validate YAML configuration
- **Exports**:
  - `ConfigLoader.load(path): AppConfig` - Main loading function
  - `AppConfig` - Root configuration type
  - All nested config types

### Module: postgres
- **Maps to capability**: Database Operations
- **Responsibility**: PostgreSQL persistence with connection pooling
- **Exports**:
  - `DatabaseFactory.create(config): DataSource`
  - `OutboxRepository` - Outbox operations
  - `InboxRepository` - Inbox operations

### Module: outbox-service
- **Maps to capability**: Outbox Processing, HTTP Publishing
- **Responsibility**: Poll outbox, route messages, publish to destinations
- **Exports**:
  - `OutboxPoller` - Main polling service
  - `MessageRouter` - Topic-based routing
  - `HttpPublisher` - HTTP destination publisher

### Module: inbox-service
- **Maps to capability**: HTTP Inbox Receiver
- **Responsibility**: Receive webhooks, deduplicate, store
- **Exports**:
  - `inboxRoutes(config): Route` - Ktor route installer
  - `IdempotencyExtractor` - JSONPath key extraction

### Module: rabbitmq
- **Maps to capability**: RabbitMQ Integration
- **Responsibility**: RabbitMQ publish and consume operations
- **Exports**:
  - `RabbitPublisher` - Publisher implementation
  - `RabbitConsumer` - Queue consumer

### Module: app
- **Maps to capability**: Application Lifecycle
- **Responsibility**: Wire everything together, run server
- **Exports**:
  - `main()` - Application entry point

</structural-decomposition>

---

<dependency-graph>

## Dependency Chain

### Foundation Layer (Phase 0)
No dependencies - these modules are built first.

- **core**: Shared domain models and interfaces. No external module dependencies.
- **config**: Configuration loading. No dependencies on other QueueBox modules.

### Data Layer (Phase 1)
Depends on Foundation Layer.

- **postgres**: Depends on [core, config]
  - Uses core models (OutboxMessage, InboxMessage)
  - Uses config for database connection settings

### Service Layer (Phase 2)
Depends on Data Layer.

- **outbox-service**: Depends on [core, config, postgres]
  - Uses core models and Publisher interface
  - Uses config for polling/routing settings
  - Uses postgres OutboxRepository for message claiming

- **inbox-service**: Depends on [core, config, postgres]
  - Uses core InboxMessage model
  - Uses config for source definitions
  - Uses postgres InboxRepository for storage

### Integration Layer (Phase 3)
Depends on Service Layer.

- **rabbitmq**: Depends on [core, config]
  - Implements core Publisher interface
  - Uses config for RabbitMQ connection settings
  - Note: Does NOT depend on postgres directly

### Application Layer (Phase 4)
Depends on all layers.

- **app**: Depends on [core, config, postgres, outbox-service, inbox-service, rabbitmq]
  - Wires all modules together
  - Starts services and server

## Dependency Diagram

```
                    ┌─────────────────────────────────────────┐
                    │              app (Phase 4)              │
                    └─────────────────────────────────────────┘
                                        │
          ┌─────────────────────────────┼─────────────────────────────┐
          ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  outbox-service │          │  inbox-service  │          │    rabbitmq     │
│    (Phase 2)    │          │    (Phase 2)    │          │    (Phase 3)    │
└─────────────────┘          └─────────────────┘          └─────────────────┘
          │                             │                             │
          └──────────────┬──────────────┘                             │
                         ▼                                            │
              ┌─────────────────┐                                     │
              │    postgres     │                                     │
              │    (Phase 1)    │                                     │
              └─────────────────┘                                     │
                         │                                            │
          ┌──────────────┴──────────────┬─────────────────────────────┘
          ▼                             ▼
┌─────────────────┐          ┌─────────────────┐
│      core       │          │     config      │
│    (Phase 0)    │          │    (Phase 0)    │
└─────────────────┘          └─────────────────┘
```

</dependency-graph>

---

<implementation-roadmap>

## Development Phases

### Phase 0: Foundation
**Goal**: Establish core domain models and configuration system that all other modules depend on.

**Entry Criteria**: Clean repository with Gradle multi-module setup

**Tasks**:
- [ ] **Setup Gradle multi-module project** (depends on: none)
  - Acceptance: All modules compile, dependencies declared correctly
  - Test: `./gradlew build` succeeds with empty modules

- [ ] **Implement core domain models** (depends on: gradle setup)
  - Acceptance: OutboxMessage, InboxMessage, MessageState, Destination types defined
  - Test: Unit tests for state transitions, data class equality

- [ ] **Implement Publisher interface** (depends on: core models)
  - Acceptance: Publisher interface with suspend publish method defined
  - Test: Interface compiles, can be implemented

- [ ] **Implement configuration data classes** (depends on: none)
  - Acceptance: All config types match YAML structure
  - Test: Serialization round-trip tests

- [ ] **Implement ConfigLoader with env substitution** (depends on: config data classes)
  - Acceptance: Loads YAML, substitutes ${VAR} patterns
  - Test: Unit tests for loading, substitution, missing var handling

- [ ] **Implement ConfigValidator** (depends on: ConfigLoader)
  - Acceptance: Validates required fields, valid URLs/ports
  - Test: Unit tests for valid/invalid configurations

**Exit Criteria**: Other modules can import core types and load configuration

**Delivers**: Foundation for all subsequent development

---

### Phase 1: Data Layer
**Goal**: Implement PostgreSQL persistence layer with connection pooling.

**Entry Criteria**: Phase 0 complete (core models, config loading)

**Tasks**:
- [ ] **Implement DatabaseFactory with HikariCP** (depends on: config)
  - Acceptance: Creates DataSource from config, connection pool works
  - Test: Integration test connecting to Postgres

- [ ] **Implement OutboxRepository.claimBatch** (depends on: DatabaseFactory, core models)
  - Acceptance: Uses FOR UPDATE SKIP LOCKED, updates state atomically
  - Test: Integration test claiming messages, concurrent claim test

- [ ] **Implement OutboxRepository state methods** (depends on: claimBatch)
  - Acceptance: markSent, markFailed, scheduleRetry, markDead work correctly
  - Test: Unit tests for each state transition

- [ ] **Implement InboxRepository.store with dedup** (depends on: DatabaseFactory, core models)
  - Acceptance: Handles INSERT with unique constraint gracefully
  - Test: Integration test storing, duplicate detection

- [ ] **Implement InboxRepository.claimPending** (depends on: store)
  - Acceptance: Claims pending messages for processing
  - Test: Integration test claiming

- [ ] **Create database migration scripts** (depends on: none)
  - Acceptance: SQL scripts create outbox/inbox tables with indexes
  - Test: Migration runs idempotently

**Exit Criteria**: Can store and retrieve messages from PostgreSQL

**Delivers**: Working persistence layer for outbox and inbox

---

### Phase 2: Core Services
**Goal**: Implement outbox polling with HTTP publishing and inbox HTTP receiver.

**Entry Criteria**: Phase 1 complete (repositories working)

**Tasks**:
- [ ] **Implement RetryStrategy** (depends on: core)
  - Acceptance: Calculates exponential backoff delays
  - Test: Unit tests for delay calculations

- [ ] **Implement MessageRouter** (depends on: config, core)
  - Acceptance: Matches topics to destinations using glob patterns
  - Test: Unit tests for pattern matching (order.*, *, etc.)

- [ ] **Implement HttpPublisher** (depends on: core Publisher interface)
  - Acceptance: POSTs to endpoint, includes required headers, handles errors
  - Test: Integration test with mock server

- [ ] **Implement OutboxPoller** (depends on: OutboxRepository, MessageRouter, HttpPublisher, RetryStrategy)
  - Acceptance: Polls, claims, routes, publishes, handles failures
  - Test: Integration test for full flow

- [ ] **Implement IdempotencyExtractor** (depends on: core)
  - Acceptance: Extracts values from JSON using JSONPath expressions
  - Test: Unit tests for various JSONPath patterns

- [ ] **Implement InboxHandler** (depends on: InboxRepository, IdempotencyExtractor)
  - Acceptance: Coordinates extraction and storage
  - Test: Unit tests with mocked repository

- [ ] **Implement inbox Ktor routes** (depends on: InboxHandler, config)
  - Acceptance: POST endpoints per configured source, proper responses
  - Test: Integration test for webhook receipt

**Exit Criteria**: End-to-end flow: app writes to outbox → QueueBox delivers to HTTP endpoint; webhook received → stored in inbox

**Delivers**: Working outbox-to-HTTP and HTTP-to-inbox flows

---

### Phase 3: Application & Docker
**Goal**: Wire application together with health checks, metrics, and Docker deployment.

**Entry Criteria**: Phase 2 complete (core services working)

**Tasks**:
- [ ] **Implement Application.kt with DI wiring** (depends on: all services)
  - Acceptance: All components initialized in correct order
  - Test: Application starts without errors

- [ ] **Implement health check endpoint** (depends on: Application, postgres)
  - Acceptance: GET /health returns component status
  - Test: Integration test for healthy/unhealthy states

- [ ] **Implement metrics endpoint** (depends on: Application)
  - Acceptance: GET /metrics returns Prometheus format counters
  - Test: Integration test for metric format

- [ ] **Implement graceful shutdown** (depends on: OutboxPoller, Server)
  - Acceptance: SIGTERM triggers orderly shutdown, in-flight messages complete
  - Test: Shutdown test with messages in flight

- [ ] **Create multi-stage Dockerfile** (depends on: Application)
  - Acceptance: Builds minimal runtime image
  - Test: `docker build` succeeds, container starts

- [ ] **Create docker-compose.yml** (depends on: Dockerfile)
  - Acceptance: Starts QueueBox + Postgres for local dev
  - Test: `docker-compose up` works, services communicate

**Exit Criteria**: Single `docker-compose up` runs full local development environment

**Delivers**: Production-ready deployment artifact

---

### Phase 4: RabbitMQ Integration
**Goal**: Add RabbitMQ as a destination for outbox and source for inbox.

**Entry Criteria**: Phase 3 complete (application running in Docker)

**Tasks**:
- [ ] **Implement RabbitConnection** (depends on: config)
  - Acceptance: Connects with auto-reconnect, handles failures gracefully
  - Test: Integration test with connection drop simulation

- [ ] **Implement RabbitPublisher** (depends on: RabbitConnection, core Publisher)
  - Acceptance: Publishes with routing key, uses confirms
  - Test: Integration test publishing to exchange

- [ ] **Integrate RabbitPublisher with OutboxPoller** (depends on: RabbitPublisher, OutboxPoller)
  - Acceptance: Routes can target RabbitMQ destination
  - Test: End-to-end test: outbox → RabbitMQ

- [ ] **Implement RabbitConsumer** (depends on: RabbitConnection, InboxRepository)
  - Acceptance: Consumes, deduplicates, stores, acks
  - Test: Integration test consuming from queue

- [ ] **Add RabbitMQ to docker-compose.yml** (depends on: docker-compose)
  - Acceptance: RabbitMQ container added, connected to QueueBox
  - Test: Full integration test with RabbitMQ

**Exit Criteria**: QueueBox can publish to RabbitMQ and consume from RabbitMQ into inbox

**Delivers**: Complete MVP with HTTP and RabbitMQ support

</implementation-roadmap>

---

<test-strategy>

## Test Pyramid

```
          /\
         /E2E\         ← 10% (Full flows: outbox→destination, webhook→inbox)
        /------\
       /Integr- \      ← 30% (Module interactions, database, HTTP clients)
      / ation   \
     /------------\
    /  Unit Tests  \   ← 60% (Pure functions, state machines, routing logic)
   /----------------\
```

## Coverage Requirements

- Line coverage: 80% minimum
- Branch coverage: 75% minimum
- Critical paths (message delivery, deduplication): 95% minimum

## Critical Test Scenarios

### OutboxRepository

**Happy path**:
- Claim batch returns up to N pending messages
- State transitions work correctly (pending→processing→sent)
- Expected: Messages claimed atomically, state updated

**Edge cases**:
- Claim with no pending messages returns empty list
- Claim with concurrent pollers (FOR UPDATE SKIP LOCKED)
- Expected: No duplicate claims, no deadlocks

**Error cases**:
- Database connection failure during claim
- Expected: Exception propagated, no partial state

### MessageRouter

**Happy path**:
- Topic "order.created" matches route "order.*"
- First matching route wins
- Expected: Correct destination returned

**Edge cases**:
- Topic with no matching routes
- Multiple matching routes (priority order)
- Wildcard "*" matches everything
- Expected: Deterministic behavior

### InboxRepository (Deduplication)

**Happy path**:
- First message with key stores successfully
- Expected: Message persisted, result indicates new

**Edge cases**:
- Duplicate key returns duplicate indicator, no error
- Different source + same key = different message (stored)
- Expected: Unique constraint on (source, idempotency_key)

**Error cases**:
- Database failure during store
- Expected: Error propagated, caller can retry

### HttpPublisher

**Happy path**:
- POST to valid endpoint returns 200
- Expected: Success result, message marked sent

**Error cases**:
- Timeout after configured duration
- 5xx response from server
- Connection refused
- Expected: Failure result with error details for retry logic

### OutboxPoller (Integration)

**End-to-end flow**:
- Message in outbox → polled → routed → published → marked sent
- Expected: Full cycle completes, message state = sent

**Retry flow**:
- Publish fails → message rescheduled → retry succeeds
- Expected: Attempt counter incremented, eventual success

**Dead letter flow**:
- Publish fails max_attempts times
- Expected: Message state = dead, no more retries

## Test Generation Guidelines

For Task Master's Surgical Test Generator:

1. **Unit tests first**: Every public function should have unit tests before integration tests
2. **Use TestContainers**: For Postgres and RabbitMQ integration tests
3. **Ktor test client**: For HTTP endpoint testing
4. **Coroutine testing**: Use `runTest` from kotlinx-coroutines-test
5. **Naming convention**: `should_expectedBehavior_when_condition`
6. **Arrange-Act-Assert**: Clear test structure

</test-strategy>

---

<architecture>

## System Components

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Application (app)                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │
│  │   Config    │  │   Health    │  │   Metrics   │  │   Server   │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
┌───────────────┐        ┌────────────────┐        ┌────────────────┐
│ Outbox Poller │        │ Inbox Receiver │        │   RabbitMQ     │
│    Service    │        │    Service     │        │   Module       │
├───────────────┤        ├────────────────┤        ├────────────────┤
│ - Poll loop   │        │ - HTTP routes  │        │ - Publisher    │
│ - Router      │        │ - Key extract  │        │ - Consumer     │
│ - Retry       │        │ - Handler      │        │ - Connection   │
└───────┬───────┘        └───────┬────────┘        └───────┬────────┘
        │                        │                         │
        └────────────────────────┼─────────────────────────┘
                                 ▼
                    ┌────────────────────────┐
                    │   PostgreSQL Module    │
                    ├────────────────────────┤
                    │ - OutboxRepository     │
                    │ - InboxRepository      │
                    │ - Connection Pool      │
                    └────────────┬───────────┘
                                 ▼
                    ┌────────────────────────┐
                    │      PostgreSQL        │
                    │  (outbox/inbox tables) │
                    └────────────────────────┘
```

## Data Models

### Outbox Message (Database)
```sql
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic TEXT NOT NULL,
    key TEXT,
    payload JSONB NOT NULL,
    state TEXT DEFAULT 'pending',  -- pending, processing, sent, failed, dead
    attempt INT DEFAULT 0,
    max_attempts INT DEFAULT 5,
    scheduled_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending ON outbox(scheduled_at) WHERE state = 'pending';
```

### Inbox Message (Database)
```sql
CREATE TABLE inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    event_type TEXT,
    payload JSONB NOT NULL,
    state TEXT DEFAULT 'pending',  -- pending, processing, processed, failed
    created_at TIMESTAMPTZ DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    UNIQUE(source, idempotency_key)
);

CREATE INDEX idx_inbox_pending ON inbox(created_at) WHERE state = 'pending';
```

## Technology Stack

| Component | Technology | Rationale |
|-----------|------------|-----------|
| Language | Kotlin 2.0+ | Coroutines for async, null safety, data classes |
| HTTP Framework | Ktor | Coroutines-native, lightweight, good for sidecars |
| Database | PostgreSQL | SKIP LOCKED support, JSONB, reliable |
| Connection Pool | HikariCP | Industry standard, excellent performance |
| Config Format | YAML | Human-readable, supports complex structures |
| Serialization | kotlinx.serialization | Native Kotlin, compile-time safe |
| Message Broker | RabbitMQ | Widely adopted, good Java client |
| Build | Gradle (Kotlin DSL) | Multi-module support, Kotlin native |
| Container | Docker | Industry standard |

**Decision: Ktor over Spring Boot**
- **Rationale**: Smaller footprint for sidecar, native coroutines, faster startup
- **Trade-offs**: Less "batteries included", more manual wiring
- **Alternatives considered**: Spring Boot (too heavy), http4k (less ecosystem)

**Decision: FOR UPDATE SKIP LOCKED over polling with flags**
- **Rationale**: True atomic claiming, no race conditions, scales to multiple instances
- **Trade-offs**: PostgreSQL specific
- **Alternatives considered**: Optimistic locking (retry overhead), Redis locks (additional dependency)

</architecture>

---

<risks>

## Technical Risks

**Risk**: JSONPath library compatibility with Kotlin coroutines
- **Impact**: Medium - Could block inbox feature
- **Likelihood**: Low - Established libraries exist
- **Mitigation**: Evaluate jayway/JsonPath early in Phase 0
- **Fallback**: Simple nested key notation (data.id) instead of full JSONPath

**Risk**: RabbitMQ Java client blocking calls in coroutine context
- **Impact**: Medium - Performance impact
- **Likelihood**: Medium - Known issue with Java clients
- **Mitigation**: Use Dispatchers.IO for RabbitMQ operations, investigate kotlin-amqp
- **Fallback**: Accept blocking calls with dedicated thread pool

**Risk**: FOR UPDATE SKIP LOCKED performance under high load
- **Impact**: High - Core functionality affected
- **Likelihood**: Low - Well-tested PostgreSQL feature
- **Mitigation**: Load test early, tune batch sizes
- **Fallback**: Partitioning by topic for parallel processing

## Dependency Risks

**Risk**: kotlinx.serialization YAML support maturity
- **Impact**: Medium - Config loading affected
- **Likelihood**: Medium - YAML format is community maintained
- **Mitigation**: Evaluate kaml library, have fallback to Jackson YAML
- **Fallback**: Use Jackson for YAML, kotlinx.serialization for JSON

## Scope Risks

**Risk**: "Just one more destination type" scope creep
- **Impact**: Medium - Delayed MVP
- **Likelihood**: High - Natural request
- **Mitigation**: Strict Phase 2 boundary - only RabbitMQ, other destinations post-MVP
- **Fallback**: Document destination plugin interface for future extensibility

**Risk**: Transform/CEL requirements creeping in
- **Impact**: High - Significant additional complexity
- **Likelihood**: Medium - Listed in out-of-scope but valuable
- **Mitigation**: Keep explicitly out of scope, document as future phase
- **Fallback**: Simple header/routing key templating only ({{ topic }})

</risks>

---

<appendix>

## References

- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [PostgreSQL FOR UPDATE SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [Ktor Documentation](https://ktor.io/docs/welcome.html)
- [RabbitMQ Java Client](https://www.rabbitmq.com/java-client.html)

## Glossary

- **Outbox Pattern**: Write events to a database table in the same transaction as business data, then process asynchronously
- **Inbox Pattern**: Store incoming messages with idempotency key for exactly-once processing
- **Idempotency Key**: Unique identifier for a message, used to detect duplicates
- **Sidecar**: Auxiliary container running alongside the main application
- **FOR UPDATE SKIP LOCKED**: PostgreSQL feature that skips locked rows instead of waiting

## Open Questions

1. Should inbox return 200 or 409 on duplicate? (Current: 200 is simpler)
2. Should we support multiple config files? (Current: Single file)
3. Dead letter queue destination or just dead state in table? (Current: Table state only)

</appendix>
