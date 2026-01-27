# QueueBox Features v1.1 - Product Requirements Document

---

<overview>

## Problem Statement

QueueBox MVP is complete but needs enhancements for production readiness and broader adoption:

1. **Observability Gap**: No structured metrics API for monitoring message throughput, latency, and error rates
2. **Database Lock-in**: Only PostgreSQL supported; SQL Server users cannot adopt QueueBox
3. **Storage Growth**: No automated cleanup of processed messages leads to unbounded table growth
4. **Configuration Inflexibility**: YAML-only config doesn't work in container environments where mounting files is difficult
5. **Rigid Payloads**: No ability to transform message payloads before delivery, limiting integration flexibility

## Target Users

**Primary: Backend/Platform Engineers** who need:
- Production-grade observability for monitoring and alerting
- SQL Server support for Microsoft-stack environments
- Configurable retention policies for compliance and storage management
- Environment-variable-based configuration for Kubernetes/container deployments
- Payload transformation for adapter/integration scenarios

## Solution

Extend QueueBox with:
1. **Metrics API** - Prometheus-compatible metrics endpoint with counters, gauges, and histograms
2. **SQL Server Support** - Additional database module for Microsoft SQL Server
3. **Retention Policies** - Configurable cleanup of processed messages by age or count
4. **Env-First Configuration** - Debezium-style environment variable support alongside YAML
5. **JSONata Transforms** - Declarative payload transformation using JSONata expressions

## Success Metrics

1. **Observability**: All key metrics exposed (messages processed, failed, latency p50/p95/p99)
2. **SQL Server**: Feature parity with PostgreSQL repository layer
3. **Retention**: Configurable cleanup executes without blocking normal operations
4. **Configuration**: 100% of YAML properties configurable via environment variables
5. **Transforms**: JSONata expressions execute with configurable timeout and resource limits

</overview>

---

<functional-decomposition>

## Capability Tree

### Capability: Observability Metrics API
Structured metrics exposure for monitoring systems.

#### Feature: Prometheus Metrics Endpoint
- **Description**: HTTP endpoint exposing metrics in Prometheus text format
- **Inputs**: HTTP GET /metrics
- **Outputs**: Prometheus-formatted metrics text
- **Behavior**: Collect all registered metrics, format as Prometheus exposition format, return with Content-Type: text/plain

#### Feature: Outbox Metrics
- **Description**: Track outbox processing performance
- **Inputs**: Message processing events
- **Outputs**: Updated metric values
- **Metrics**:
  - `queuebox_outbox_messages_total{status=sent|failed|dead}` - Counter
  - `queuebox_outbox_messages_pending` - Gauge
  - `queuebox_outbox_processing_duration_seconds` - Histogram (p50, p95, p99)
  - `queuebox_outbox_publish_duration_seconds{destination_type=http|rabbitmq}` - Histogram
  - `queuebox_outbox_retry_count_total` - Counter
  - `queuebox_outbox_batch_size` - Histogram

#### Feature: Inbox Metrics
- **Description**: Track inbox receiving performance
- **Inputs**: Webhook/message receipt events
- **Outputs**: Updated metric values
- **Metrics**:
  - `queuebox_inbox_messages_total{status=new|duplicate}` - Counter
  - `queuebox_inbox_messages_pending` - Gauge
  - `queuebox_inbox_receive_duration_seconds` - Histogram

#### Feature: Database Metrics
- **Description**: Track database connection pool health
- **Inputs**: HikariCP pool events
- **Outputs**: Pool metrics
- **Metrics**:
  - `queuebox_db_connections_active` - Gauge
  - `queuebox_db_connections_idle` - Gauge
  - `queuebox_db_connections_pending` - Gauge
  - `queuebox_db_connection_timeout_total` - Counter

#### Feature: Application Metrics
- **Description**: Track application-level health
- **Inputs**: Application lifecycle events
- **Outputs**: Health metrics
- **Metrics**:
  - `queuebox_uptime_seconds` - Gauge
  - `queuebox_info{version=x.y.z}` - Info gauge

---

### Capability: SQL Server Database Support
Repository layer implementation for Microsoft SQL Server.

#### Feature: SQL Server Connection Management
- **Description**: Manage SQL Server connection pool with HikariCP
- **Inputs**: SQL Server configuration (host, port, database, credentials)
- **Outputs**: Configured DataSource
- **Behavior**: Use Microsoft JDBC driver, configure HikariCP, handle SQL Server-specific connection settings

#### Feature: SQL Server Outbox Repository
- **Description**: Outbox operations using SQL Server syntax
- **Inputs**: OutboxMessage operations
- **Outputs**: SQL Server-compatible queries
- **Behavior**:
  - Use `WITH (ROWLOCK, UPDLOCK, READPAST)` hints instead of `FOR UPDATE SKIP LOCKED`
  - Use `NEWID()` instead of `gen_random_uuid()`
  - Handle datetime2 instead of timestamptz
  - Use MERGE for upsert operations

#### Feature: SQL Server Inbox Repository
- **Description**: Inbox operations with SQL Server deduplication
- **Inputs**: InboxMessage operations
- **Outputs**: SQL Server-compatible queries
- **Behavior**:
  - Use MERGE statement for insert-if-not-exists
  - Handle unique constraint with SQL Server error codes
  - Use `WITH (ROWLOCK, UPDLOCK, READPAST)` for batch claiming

#### Feature: SQL Server Migration Scripts
- **Description**: Database schema for SQL Server
- **Inputs**: None
- **Outputs**: T-SQL migration scripts
- **Behavior**: Create outbox/inbox tables with appropriate indexes, use SQL Server data types

#### Feature: Database Provider Abstraction
- **Description**: Abstract repository interface with provider implementations
- **Inputs**: Database type from configuration
- **Outputs**: Appropriate repository implementation
- **Behavior**: Factory pattern to instantiate PostgreSQL or SQL Server repositories based on config

---

### Capability: Message Retention and Cleanup
Automated cleanup of processed messages.

#### Feature: Retention Configuration
- **Description**: Configure retention policies in YAML
- **Inputs**: Retention settings in configuration
- **Outputs**: Parsed retention policy
- **Configuration**:
  ```yaml
  retention:
    enabled: true
    outbox:
      policy: age  # age | count | disabled
      max_age: 7d  # for age policy
      max_count: 100000  # for count policy
      cleanup_interval: 1h
      batch_size: 1000
    inbox:
      policy: age
      max_age: 30d
      cleanup_interval: 6h
      batch_size: 1000
  ```

#### Feature: Age-Based Cleanup
- **Description**: Delete messages older than configured age
- **Inputs**: Max age duration, current time
- **Outputs**: Deleted message count
- **Behavior**: Delete WHERE state IN ('sent', 'dead', 'processed') AND updated_at < now() - max_age

#### Feature: Count-Based Cleanup
- **Description**: Keep only N most recent completed messages
- **Inputs**: Max count, current count
- **Outputs**: Deleted message count
- **Behavior**: Delete oldest completed messages when count exceeds threshold

#### Feature: Cleanup Scheduler
- **Description**: Background job executing cleanup at configured intervals
- **Inputs**: Cleanup interval, retention policy
- **Outputs**: Periodic cleanup execution
- **Behavior**: Coroutine-based scheduler, delete in batches to avoid long locks, log cleanup results

#### Feature: Cleanup Metrics
- **Description**: Track cleanup operations
- **Inputs**: Cleanup execution events
- **Outputs**: Cleanup metrics
- **Metrics**:
  - `queuebox_cleanup_messages_deleted_total{table=outbox|inbox}` - Counter
  - `queuebox_cleanup_duration_seconds{table=outbox|inbox}` - Histogram
  - `queuebox_cleanup_last_run_timestamp{table=outbox|inbox}` - Gauge

---

### Capability: Environment Variable Configuration
Debezium-style env variable support for container deployments.

#### Feature: Env Variable Naming Convention
- **Description**: Map YAML paths to environment variable names
- **Inputs**: YAML property path
- **Outputs**: Environment variable name
- **Behavior**:
  - Replace dots with underscores
  - Replace hyphens with underscores
  - Uppercase all characters
  - Prefix with `QUEUEBOX_`
  - Example: `database.pool-size` → `QUEUEBOX_DATABASE_POOL_SIZE`

#### Feature: Env-Only Mode
- **Description**: Run without YAML file using only environment variables
- **Inputs**: Environment variables, no config file
- **Outputs**: Complete configuration object
- **Behavior**:
  - Detect missing config file
  - Build configuration entirely from environment variables
  - Support all configuration properties
  - Provide clear error messages for missing required properties

#### Feature: Env Override Mode
- **Description**: YAML as base with environment variable overrides
- **Inputs**: YAML configuration + environment variables
- **Outputs**: Merged configuration
- **Behavior**:
  - Load YAML configuration first
  - Scan for QUEUEBOX_* environment variables
  - Override matching YAML properties with env values
  - Env variables take precedence over YAML

#### Feature: Complex Type Handling
- **Description**: Handle arrays and objects in environment variables
- **Inputs**: Complex configuration structures
- **Outputs**: Parsed complex types
- **Behavior**:
  - Arrays: Use indexed naming `QUEUEBOX_ROUTES_0_TOPIC`, `QUEUEBOX_ROUTES_1_TOPIC`
  - Objects: Use dot-path naming `QUEUEBOX_DESTINATIONS_HTTP1_URL`
  - JSON strings: Support `QUEUEBOX_DESTINATIONS='[{"name":"http1",...}]'` for complex structures

#### Feature: Configuration Documentation Generator
- **Description**: Generate documentation of all configurable properties
- **Inputs**: Configuration class definitions
- **Outputs**: Markdown documentation with YAML paths and env variable names
- **Behavior**: Introspect config classes, generate table of all properties with both naming conventions

---

### Capability: Message Transforms (JSONata)
Declarative payload transformation using JSONata expressions.

#### Feature: Transform Configuration
- **Description**: Configure transforms per route or destination
- **Inputs**: Transform configuration in YAML
- **Outputs**: Parsed transform rules
- **Configuration**:
  ```yaml
  routes:
    - topic: "order.*"
      destination: http-webhook
      transform:
        expression: |
          {
            "orderId": id,
            "customer": customer.name,
            "total": items.(price * quantity) ~> $sum(),
            "timestamp": $now()
          }
        timeout_ms: 100

  destinations:
    - name: http-webhook
      type: http
      url: https://api.example.com/webhook
      # Destination-level transform (applied after route transform)
      transform:
        expression: '{ "payload": $, "source": "queuebox" }'
  ```

#### Feature: JSONata Expression Engine
- **Description**: Evaluate JSONata expressions against message payloads
- **Inputs**: JSON payload, JSONata expression
- **Outputs**: Transformed JSON payload
- **Behavior**:
  - Use Dashjoin jsonata-java library for 100% compatibility
  - Configure execution timeout (default 100ms)
  - Configure max recursion depth
  - Cache compiled expressions for performance

#### Feature: Transform Pipeline
- **Description**: Apply transforms in correct order
- **Inputs**: Original payload, route transform, destination transform
- **Outputs**: Final transformed payload
- **Behavior**:
  1. Apply route-level transform (if configured)
  2. Apply destination-level transform (if configured)
  3. Transforms are optional at each level
  4. Pass-through if no transform configured

#### Feature: Transform Error Handling
- **Description**: Handle transform failures gracefully
- **Inputs**: Transform execution result
- **Outputs**: Success or error with details
- **Behavior**:
  - Timeout: Fail with timeout error, message goes to retry
  - Invalid expression: Fail at startup with clear error
  - Runtime error: Log error, fail message (goes to retry/dead)
  - Option to skip transform on error and deliver original payload

#### Feature: Transform Context Variables
- **Description**: Provide message metadata as transform context
- **Inputs**: Message metadata
- **Outputs**: Context variables available in expressions
- **Variables**:
  - `$messageId` - Message UUID
  - `$topic` - Message topic
  - `$attempt` - Current attempt number
  - `$timestamp` - Message creation timestamp
  - `$source` - Source name (for inbox)

#### Feature: Transform Testing Endpoint
- **Description**: Test transform expressions without sending messages
- **Inputs**: POST /admin/transform/test with payload and expression
- **Outputs**: Transformed result or error details
- **Behavior**: Evaluate expression against provided payload, return result for debugging

</functional-decomposition>

---

<structural-decomposition>

## Repository Structure Additions

```
queuebox/
├── core/
│   └── src/main/kotlin/
│       ├── ... (existing)
│       └── metrics/
│           └── Metrics.kt           # Metric definitions
├── config/
│   └── src/main/kotlin/
│       ├── ... (existing)
│       ├── EnvConfigLoader.kt       # Environment variable loading
│       ├── RetentionConfig.kt       # Retention policy config
│       └── TransformConfig.kt       # Transform config
├── postgres/
│   └── src/main/kotlin/
│       ├── ... (existing)
│       └── PostgresRepositoryFactory.kt
├── sqlserver/                        # NEW MODULE
│   └── src/main/kotlin/
│       ├── SqlServerDatabaseFactory.kt
│       ├── SqlServerOutboxRepository.kt
│       ├── SqlServerInboxRepository.kt
│       └── SqlServerRepositoryFactory.kt
│   └── src/main/resources/
│       └── migrations/
│           └── V1__create_tables.sql
├── outbox-service/
│   └── src/main/kotlin/
│       ├── ... (existing)
│       ├── RetentionService.kt      # Cleanup scheduler
│       └── transform/
│           ├── TransformEngine.kt   # JSONata wrapper
│           └── TransformPipeline.kt # Transform orchestration
├── app/
│   └── src/main/kotlin/
│       ├── ... (existing)
│       ├── MetricsRegistry.kt       # Metrics collection
│       └── AdminRoutes.kt           # Transform test endpoint
└── build.gradle.kts
```

## New Module: sqlserver

- **Maps to capability**: SQL Server Database Support
- **Responsibility**: SQL Server-specific repository implementations
- **Dependencies**: core, config
- **Exports**:
  - `SqlServerOutboxRepository` - Outbox operations for SQL Server
  - `SqlServerInboxRepository` - Inbox operations for SQL Server
  - `SqlServerRepositoryFactory` - Factory for creating SQL Server repos

</structural-decomposition>

---

<dependency-graph>

## Feature Dependencies

### Phase 1: Foundation (No Dependencies)
- Environment Variable Configuration
- Retention Configuration parsing
- Transform Configuration parsing

### Phase 2: Core Features
- Observability Metrics API (depends on: core metrics definitions)
- SQL Server repositories (depends on: core models, config)
- JSONata Transform Engine (depends on: config, core)

### Phase 3: Integration
- Retention Service (depends on: repositories, config, metrics)
- Transform Pipeline (depends on: Transform Engine, Outbox Poller)
- Database Provider Abstraction (depends on: postgres repos, sqlserver repos)

### Phase 4: Polish
- Transform Testing Endpoint (depends on: Transform Engine)
- Configuration Documentation Generator (depends on: config)

## Dependency Diagram

```
                    ┌─────────────────────────────────────────┐
                    │              app                        │
                    │  (AdminRoutes, MetricsRegistry)         │
                    └─────────────────────────────────────────┘
                                        │
          ┌─────────────────────────────┼─────────────────────────────┐
          ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  outbox-service │          │  RetentionSvc   │          │    sqlserver    │
│  + Transform    │          │                 │          │    (new)        │
└─────────────────┘          └─────────────────┘          └─────────────────┘
          │                             │                             │
          └──────────────┬──────────────┴─────────────────────────────┘
                         ▼
              ┌─────────────────┐
              │  config         │
              │  + EnvLoader    │
              │  + Retention    │
              │  + Transform    │
              └─────────────────┘
                         │
                         ▼
              ┌─────────────────┐
              │      core       │
              │  + Metrics      │
              └─────────────────┘
```

</dependency-graph>

---

<implementation-roadmap>

## Development Phases

### Phase 1: Environment Variable Configuration
**Goal**: Enable container-friendly configuration without YAML files.

**Tasks**:
- [ ] Define environment variable naming convention (QUEUEBOX_*)
- [ ] Implement EnvConfigLoader for env-only mode
- [ ] Implement env override mode (YAML + env overrides)
- [ ] Handle complex types (arrays via indexed naming, JSON strings)
- [ ] Add configuration validation for env-based config
- [ ] Create configuration documentation generator
- [ ] Test all existing YAML properties work via env vars

**Exit Criteria**: QueueBox runs with zero YAML files using only environment variables

---

### Phase 2: Observability Metrics API
**Goal**: Expose production-grade metrics for monitoring.

**Tasks**:
- [ ] Add Micrometer dependency for metrics abstraction
- [ ] Define metric types in core module (counters, gauges, histograms)
- [ ] Implement outbox metrics collection in OutboxPoller
- [ ] Implement inbox metrics collection in InboxHandler
- [ ] Implement database pool metrics from HikariCP
- [ ] Implement application info metrics (version, uptime)
- [ ] Create /metrics endpoint with Prometheus format
- [ ] Add histogram buckets for latency metrics (p50, p95, p99)

**Exit Criteria**: Prometheus can scrape /metrics and display dashboards

---

### Phase 3: Message Retention and Cleanup
**Goal**: Automated cleanup of processed messages.

**Tasks**:
- [ ] Define retention configuration schema
- [ ] Implement age-based cleanup query
- [ ] Implement count-based cleanup query
- [ ] Create cleanup scheduler (coroutine-based)
- [ ] Implement batched deletion to avoid long locks
- [ ] Add cleanup metrics
- [ ] Test cleanup doesn't interfere with normal operations
- [ ] Document retention configuration options

**Exit Criteria**: Processed messages automatically cleaned up per policy

---

### Phase 4: SQL Server Support
**Goal**: Full SQL Server support with feature parity to PostgreSQL.

**Tasks**:
- [ ] Add SQL Server JDBC driver dependency
- [ ] Create sqlserver module with Gradle configuration
- [ ] Implement SqlServerDatabaseFactory with HikariCP
- [ ] Create SQL Server migration scripts (T-SQL)
- [ ] Implement SqlServerOutboxRepository with UPDLOCK hints
- [ ] Implement SqlServerInboxRepository with MERGE
- [ ] Create DatabaseProviderFactory abstraction
- [ ] Update config to support database.type: postgresql | sqlserver
- [ ] Integration tests with SQL Server TestContainer
- [ ] Document SQL Server-specific configuration

**Exit Criteria**: All existing tests pass with SQL Server backend

---

### Phase 5: JSONata Message Transforms
**Goal**: Declarative payload transformation.

**Tasks**:
- [ ] Add Dashjoin jsonata-java dependency
- [ ] Define transform configuration schema
- [ ] Implement TransformEngine wrapper with timeout/safety
- [ ] Implement expression caching for performance
- [ ] Create TransformPipeline for route+destination transforms
- [ ] Add transform context variables ($messageId, $topic, etc.)
- [ ] Integrate transforms into OutboxPoller flow
- [ ] Implement transform error handling strategies
- [ ] Create /admin/transform/test endpoint
- [ ] Document transform configuration and JSONata usage

**Exit Criteria**: Messages can be transformed before delivery using JSONata

</implementation-roadmap>

---

<technology-decisions>

## Key Technology Choices

### Metrics: Micrometer
- **Rationale**: Vendor-neutral metrics facade, supports Prometheus, Datadog, etc.
- **Trade-offs**: Additional dependency, but standard in JVM ecosystem
- **Alternatives**: Direct Prometheus client (less portable)

### JSONata Library: Dashjoin jsonata-java
- **Rationale**: 100% compatible port of reference implementation, timeout support, active maintenance
- **Trade-offs**: Not as widely used as IBM version
- **Alternatives**: IBM JSONata4Java (larger community, but less strict compatibility)

### SQL Server Driver: Microsoft JDBC
- **Rationale**: Official driver, best compatibility
- **Trade-offs**: Requires Microsoft license acceptance
- **Alternatives**: jTDS (open source, but less maintained)

### Environment Config: Custom Implementation
- **Rationale**: Debezium-style naming convention matches user expectations for container deployments
- **Trade-offs**: Custom code vs using existing config libraries
- **Alternatives**: Spring Config (too heavy), Hoplite (good but different conventions)

</technology-decisions>

---

<risks>

## Technical Risks

**Risk**: SQL Server row locking behaves differently than PostgreSQL SKIP LOCKED
- **Impact**: High - Core functionality affected
- **Mitigation**: Extensive testing with concurrent operations, document differences
- **Fallback**: Advisory locking pattern if hints don't provide same guarantees

**Risk**: JSONata expressions can be computationally expensive
- **Impact**: Medium - Could slow down message processing
- **Mitigation**: Strict timeouts, expression complexity limits, caching
- **Fallback**: Limit to simple expressions, document performance guidelines

**Risk**: Environment variable configuration with complex types is error-prone
- **Impact**: Medium - User configuration errors
- **Mitigation**: Clear error messages, validation, documentation generator
- **Fallback**: Require YAML for complex configurations, env for simple overrides

</risks>

</functional-decomposition>
