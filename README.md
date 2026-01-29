# QueueBox

A message processing service implementing reliable inbox and outbox patterns for distributed systems. QueueBox handles message ingestion, deduplication, transformation, routing, and delivery with automatic retries and dead-letter handling.

## Why QueueBox?

Distributed systems face common messaging challenges: lost webhooks, duplicate events, failed deliveries, and inconsistent data. QueueBox solves these by:

- **Deduplicating incoming messages** using idempotency keys — receive the same webhook twice, store it once
- **Guaranteeing delivery** with exponential backoff retries and dead-letter queues
- **Transforming payloads** using JSONata expressions at ingestion, routing, or delivery time
- **Routing messages** to different destinations based on topic patterns
- **Cleaning up old data** automatically with configurable retention policies

## Quick Start

### Using Docker Compose

```bash
git clone https://github.com/your-org/queuebox.git
cd queuebox
docker compose up -d
```

This starts QueueBox with PostgreSQL. The service is available at `http://localhost:8080`.

To include RabbitMQ:

```bash
docker compose --profile rabbitmq up -d
```

### Manual Setup

**Requirements:** JDK 21+, PostgreSQL 14+

1. Create a PostgreSQL database:
```sql
CREATE DATABASE queuebox;
```

2. Configure `config/src/main/resources/queuebox.yml` or set environment variables:
```bash
export DB_URL=jdbc:postgresql://localhost:5432/queuebox
export DB_USER=postgres
export DB_PASSWORD=secret
```

3. Run the application:
```bash
./gradlew run
```

## Configuration

QueueBox uses YAML configuration with environment variable substitution. The config file is at `config/src/main/resources/queuebox.yml`.

### Minimal Configuration

```yaml
database:
  url: jdbc:postgresql://localhost:5432/queuebox
  username: queuebox
  password: ${DB_PASSWORD}

destinations:
  my-api:
    type: http
    baseUrl: https://api.example.com
    path: /webhooks

routes:
  - topicPattern: "order.*"
    destination: my-api

sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
```

### Full Configuration Reference

```yaml
server:
  httpPort: 8080                          # HTTP server port

database:
  type: postgresql                        # 'postgresql' or 'sqlserver'
  url: jdbc:postgresql://localhost:5432/queuebox
  username: queuebox
  password: ${DB_PASSWORD}
  poolSize: 10                            # Connection pool size
  connectionTimeoutMs: 30000              # Connection timeout
  outboxTableName: outbox                 # Custom table name (default: outbox)
  inboxTableName: inbox                   # Custom table name (default: inbox)

outbox:
  pollIntervalMs: 100                     # How often to check for pending messages
  batchSize: 100                          # Messages per poll cycle
  retryBaseDelayMs: 1000                  # Base delay for exponential backoff
  maxAttempts: 5                          # Max delivery attempts before dead-letter

inbox:
  basePath: /inbox                        # Base path for inbox HTTP endpoints

# Where messages get delivered
destinations:
  webhook-api:
    type: http
    baseUrl: https://api.example.com
    path: /webhooks
    timeoutMs: 30000
    headers:
      X-Custom-Header: static-value
    auth:                                 # Optional: authentication
      type: oauth2                        # See Authentication section
      clientId: ${CLIENT_ID}
      clientSecret: ${CLIENT_SECRET}
      tokenUrl: https://auth.example.com/oauth/token
    transform:                            # Optional: transform before delivery
      expression: '{ "payload": $, "source": "queuebox" }'

  events-exchange:
    type: rabbitmq
    url: amqp://localhost:5672
    exchange: events
    exchangeType: topic                   # 'topic', 'direct', 'fanout'

# Routing rules - match topics to destinations
routes:
  - topicPattern: "order.*"               # Glob pattern matching
    destination: webhook-api
    transform:                            # Optional: transform at route level
      expression: |
        {
          "orderId": id,
          "total": items.(price * quantity) ~> $sum()
        }
      timeoutMs: 150
      onError: fail                       # 'fail', 'skip', or 'dead'

  - topicPattern: "event.**"
    destination: events-exchange
    routingKeyTemplate: "{{ topic }}"              # For RabbitMQ routing keys
    routingKeyMissingFieldDefault: "unknown"      # Default when field is missing

# Where messages come from
sources:
  stripe:
    type: http
    path: /stripe                         # Endpoint: POST /inbox/stripe
    idempotencyKeyPath: $.id              # JSONPath to extract idempotency key
    eventTypePath: $.type                 # Optional: extract event type
    aggregateIdPath: $.customer_id        # Optional: for ordered processing
    auth:                                 # Optional: validate incoming requests
      type: hmac                          # See Authentication section
      secret: ${STRIPE_WEBHOOK_SECRET}
      headerName: Stripe-Signature
      signaturePrefix: "v1="
    transform:                            # Optional: transform on ingestion
      expression: '{ "event": $, "receivedAt": $now() }'

  orders-queue:
    type: rabbitmq
    queueName: incoming-orders
    connectionUrl: amqp://localhost:5672
    idempotencyKeyPath: $.messageId
    aggregateIdPath: $.orderId            # Optional: for ordered processing
    prefetchCount: 10

# Automatic cleanup of old messages
retention:
  enabled: true
  outbox:
    policy: age                           # 'age', 'count', or 'disabled'
    maxAge: 7d                            # Keep messages younger than 7 days
    cleanupInterval: 1h                   # Run cleanup every hour
    batchSize: 1000                       # Delete in batches
  inbox:
    policy: count
    maxCount: 100000                      # Keep most recent 100k messages
    cleanupInterval: 6h
    batchSize: 1000
```

### Environment Variable Overrides

Any config value can be overridden via environment variables using the `${VAR_NAME}` syntax with optional defaults:

```yaml
database:
  url: ${DB_URL:-jdbc:postgresql://localhost:5432/queuebox}
  password: ${DB_PASSWORD}  # Required, no default
```

## HTTP API

### Health & Status

| Endpoint | Description |
|----------|-------------|
| `GET /` | Basic health check — returns "QueueBox is running!" |
| `GET /health` | Detailed health status with database connectivity |
| `GET /metrics` | Prometheus metrics |

### Inbox Endpoints

Messages are received at `/inbox/{source}` where `{source}` matches a configured source name.

```bash
# Send a message to the 'stripe' source
curl -X POST http://localhost:8080/inbox/stripe \
  -H "Content-Type: application/json" \
  -d '{"id": "evt_123", "type": "payment.completed", "data": {...}}'
```

Response codes:
- `200` — Message accepted (new or duplicate)
- `400` — Invalid payload or missing idempotency key
- `404` — Unknown source

### Admin Endpoints

```bash
# Test a JSONata transform expression
curl -X POST http://localhost:8080/admin/transform/test \
  -H "Content-Type: application/json" \
  -d '{
    "expression": "{ \"total\": items.(price * qty) ~> $sum() }",
    "payload": {"items": [{"price": 10, "qty": 2}, {"price": 5, "qty": 3}]},
    "mockTopic": "order.created"
  }'
```

## Message Flow

### Inbox (Receiving Messages)

1. External system sends webhook to `/inbox/{source}`
2. QueueBox extracts idempotency key using configured JSONPath
3. Duplicate check — if key exists for this source, return 200 (idempotent)
4. Optional transform applied to payload
5. Message stored in `inbox` table with status `pending`
6. Return 200 OK

### Outbox (Delivering Messages)

1. Your application inserts messages into the `outbox` table
2. Poller retrieves pending messages in batches
3. Router matches topic against route patterns
4. Optional transforms applied (route-level, then destination-level)
5. Message published to destination (HTTP or RabbitMQ)
6. On success: mark as `sent`
7. On failure: increment attempt, schedule retry with exponential backoff
8. After max attempts: mark as `dead` (dead-letter)

## Transforms

QueueBox uses [JSONata](https://jsonata.org/) for payload transformations. Transforms can be applied at three levels:

1. **Source level** — transform incoming messages on ingestion
2. **Route level** — transform before routing
3. **Destination level** — transform before delivery

### Context Variables

Transforms have access to context variables:

| Variable | Description |
|----------|-------------|
| `$messageId` | UUID of the message |
| `$topic` | Message topic |
| `$attempt` | Current delivery attempt (1-based) |
| `$timestamp` | Current ISO timestamp |
| `$source` | Source name (inbox only) |

### Examples

**Reshape payload:**
```jsonata
{
  "orderId": id,
  "customer": customer.name,
  "total": items.(price * quantity) ~> $sum(),
  "processedAt": $timestamp
}
```

**Filter fields:**
```jsonata
$ ~> |$|{}, ['password', 'secret', 'token']|
```

**Conditional logic:**
```jsonata
status = 'paid' ? { "action": "fulfill", "orderId": id } : { "action": "remind", "orderId": id }
```

### Error Handling

Configure what happens when a transform fails:

```yaml
transform:
  expression: "..."
  onError: fail    # fail | skip | dead
```

- `fail` — Mark message as failed, retry later (default)
- `skip` — Skip this message, mark as sent
- `dead` — Move directly to dead-letter

## Authentication

QueueBox supports authentication for both incoming webhooks (inbox) and outgoing HTTP requests (destinations).

### Inbox Authentication

Protect your inbox endpoints from unauthorized requests.

**Bearer Token:**
```yaml
sources:
  secure-webhook:
    type: http
    path: /secure
    idempotencyKeyPath: $.id
    auth:
      type: bearer
      token: ${WEBHOOK_TOKEN}
```

**API Key:**
```yaml
auth:
  type: api-key
  headerName: X-API-Key           # Default header name
  key: ${API_KEY}
```

**HMAC Signature** (for Stripe, GitHub, etc.):
```yaml
auth:
  type: hmac
  secret: ${WEBHOOK_SECRET}
  headerName: X-Signature         # Header containing the signature
  algorithm: HmacSHA256           # HmacSHA256, HmacSHA1, etc.
  signaturePrefix: "sha256="      # Prefix before the signature
  timestampHeader: X-Timestamp    # Optional: for replay protection
  timestampTolerance: 300000      # Max age in ms (default: 5 min)
```

### Destination Authentication

Authenticate outgoing HTTP requests to protected APIs.

**OAuth2 Client Credentials:**
```yaml
destinations:
  protected-api:
    type: http
    baseUrl: https://api.example.com
    auth:
      type: oauth2
      clientId: ${CLIENT_ID}
      clientSecret: ${CLIENT_SECRET}
      tokenUrl: https://auth.example.com/oauth/token
      scope: api:write            # Optional scope
      extraParams:                # Optional additional params
        audience: https://api.example.com
```

**HTTP Basic:**
```yaml
auth:
  type: basic
  username: ${API_USER}
  password: ${API_PASSWORD}
```

**Custom Header:**
```yaml
auth:
  type: header
  headerName: Authorization       # Or any custom header
  headerValue: "Bearer ${STATIC_TOKEN}"
```

## Aggregate Ordering

When processing inbox messages, QueueBox can ensure messages for the same aggregate (e.g., customer, order) are processed in order.

Configure `aggregateIdPath` to extract the aggregate identifier:

```yaml
sources:
  orders:
    type: http
    path: /orders
    idempotencyKeyPath: $.eventId
    aggregateIdPath: $.orderId    # Messages for same order processed in sequence
```

**How it works:**
- Messages with the same aggregate ID are processed one at a time, in creation order
- Messages without an aggregate ID are processed independently (no ordering guarantee)
- If a message for an aggregate is being processed, other messages for that aggregate wait
- Different aggregates are processed in parallel

This prevents race conditions like processing "order shipped" before "order created".

## Routing Key Templates

For RabbitMQ destinations, you can dynamically construct routing keys from message data:

```yaml
routes:
  - topicPattern: "order.*"
    destination: events-exchange
    routingKeyTemplate: "{{ region }}.{{ priority }}.{{ topic }}"
    routingKeyMissingFieldDefault: "default"
```

**Template variables:**
- `{{ topic }}` — The message topic
- `{{ fieldName }}` — Any top-level field from the message payload
- Nested fields: `{{ customer.region }}`

If a field is missing, the `routingKeyMissingFieldDefault` value is used (or empty string if not configured).

## Metrics

QueueBox exposes Prometheus metrics at `/metrics`:

**Outbox metrics:**
- `queuebox_outbox_messages_total{status}` — Counter by status (sent, failed, dead)
- `queuebox_outbox_messages_pending` — Current pending messages gauge
- `queuebox_outbox_processing_duration_seconds` — Processing time histogram
- `queuebox_outbox_publish_duration_seconds{destination_type}` — Publish time by destination

**Inbox metrics:**
- `queuebox_inbox_messages_total{status}` — Counter by status (new, duplicate)

**Cleanup metrics:**
- `queuebox_cleanup_messages_deleted_total{table}` — Deleted messages counter
- `queuebox_cleanup_duration_seconds{table}` — Cleanup run duration
- `queuebox_cleanup_last_run_timestamp{table}` — Last cleanup timestamp

**System metrics:**
- `queuebox_uptime_seconds` — Application uptime
- `queuebox_info{version}` — Application info gauge

## Database Schema

QueueBox creates two tables:

**outbox:**
```
id              UUID PRIMARY KEY
topic           VARCHAR(255)
key             VARCHAR(255)        -- Optional partition/ordering key
payload         JSONB
headers         JSONB
state           VARCHAR(20)         -- 'pending', 'sent', 'dead'
attempt         INTEGER
max_attempts    INTEGER
scheduled_at    TIMESTAMP
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

**inbox:**
```
id              UUID PRIMARY KEY
source          VARCHAR(255)
idempotency_key VARCHAR(255)        -- Unique per source
aggregate_id    VARCHAR(255)
event_type      VARCHAR(255)
payload         JSONB
state           VARCHAR(20)         -- 'pending', 'processed'
created_at      TIMESTAMP
processed_at    TIMESTAMP
```

## Database Support

QueueBox supports:

- **PostgreSQL** (recommended) — Full support with JSONB
- **SQL Server** — Enterprise option with equivalent functionality

Configure via `database.type`:

```yaml
database:
  type: sqlserver
  url: jdbc:sqlserver://localhost:1433;databaseName=queuebox
```

### Custom Table and Column Names

QueueBox can integrate with existing database schemas by configuring custom table and column names:

```yaml
database:
  url: jdbc:postgresql://localhost:5432/mydb
  username: user
  password: pass
  outboxTableName: my_outbox_table        # Default: outbox
  inboxTableName: my_inbox_table          # Default: inbox
  columnMapping:
    outbox:
      id: message_id                      # Default: id
      topic: event_topic                  # Default: topic
      payload: event_data                 # Default: payload
      state: status                       # Default: state
      # ... other columns
    inbox:
      source: origin_system               # Default: source
      idempotencyKey: dedup_key           # Default: idempotency_key
      # ... other columns
```

**Available column mappings:**

| Outbox Column | Default | Description |
|---------------|---------|-------------|
| `id` | id | UUID primary key |
| `topic` | topic | Message topic |
| `key` | key | Partition/ordering key |
| `payload` | payload | JSON payload |
| `headers` | headers | Message headers |
| `state` | state | Message state |
| `attempt` | attempt | Current attempt count |
| `maxAttempts` | max_attempts | Maximum attempts |
| `scheduledAt` | scheduled_at | Scheduled delivery time |
| `createdAt` | created_at | Creation timestamp |
| `updatedAt` | updated_at | Last update timestamp |

| Inbox Column | Default | Description |
|--------------|---------|-------------|
| `id` | id | UUID primary key |
| `source` | source | Source identifier |
| `idempotencyKey` | idempotency_key | Deduplication key |
| `aggregateId` | aggregate_id | Aggregate identifier |
| `eventType` | event_type | Event type |
| `payload` | payload | JSON payload |
| `state` | state | Message state |
| `createdAt` | created_at | Creation timestamp |
| `processedAt` | processed_at | Processing timestamp |

## Usage Patterns

### Webhook Receiver

Receive webhooks from third-party services with automatic deduplication:

```yaml
sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
    eventTypePath: $.type

  github:
    type: http
    path: /github
    idempotencyKeyPath: $.delivery
    eventTypePath: $.action
```

### Event Fan-out

Route events to multiple destinations based on type:

```yaml
destinations:
  analytics:
    type: http
    baseUrl: https://analytics.internal

  notifications:
    type: http
    baseUrl: https://notifications.internal

routes:
  - topicPattern: "order.created"
    destination: analytics

  - topicPattern: "order.completed"
    destination: notifications
```

### Message Bridge

Bridge messages between RabbitMQ and HTTP:

```yaml
sources:
  orders-queue:
    type: rabbitmq
    queueName: orders
    connectionUrl: amqp://rabbitmq:5672
    idempotencyKeyPath: $.id

destinations:
  order-service:
    type: http
    baseUrl: https://orders.internal/api

routes:
  - topicPattern: "**"
    destination: order-service
```

## Development

```bash
# Build
./gradlew build

# Run tests
./gradlew check

# Run with live reload
./gradlew run --continuous
```

### Project Structure

```
queuebox/
├── app/                    # Main application, HTTP server
├── config/                 # Configuration loading and validation
├── core/                   # Domain models and interfaces
├── inbox-service/          # Inbox handling logic
├── outbox-service/         # Outbox polling and delivery
├── postgres/               # PostgreSQL repository implementation
├── sqlserver/              # SQL Server repository implementation
└── rabbitmq/               # RabbitMQ consumer and publisher
```

## Roadmap

**Planned features:**

- [ ] Admin UI for monitoring and dead-letter management
- [ ] Kafka destination support
- [ ] Message replay from dead-letter
- [ ] Rate limiting per destination
- [ ] OpenTelemetry tracing integration
- [ ] Kubernetes Helm chart

## License

MIT
