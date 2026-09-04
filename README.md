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

### Building the Docker Image

**Local build:**

```bash
docker build -t queuebox .
```

**Multi-platform build** (for cross-platform compatibility):

```bash
# Create a builder instance (one-time setup)
docker buildx create --name queuebox-builder --use

# Build for multiple architectures
docker buildx build --platform linux/amd64,linux/arm64 -t queuebox .
```

**Build and push to Docker Hub:**

```bash
# Tag with your Docker Hub username
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t yourusername/queuebox:latest \
  -t yourusername/queuebox:1.0.0 \
  --push .
```

**Build with cloud builder** (faster for multi-platform builds):

```bash
# Use Docker's cloud build infrastructure
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --builder cloud-yourusername-default \
  -t yourusername/queuebox:latest \
  --push .
```

### Manual Setup

**Requirements:** JDK 21+, PostgreSQL 14+

1. Create a PostgreSQL database:
```sql
CREATE DATABASE queuebox;
```

QueueBox creates its own tables. At startup it applies the bundled migrations with Flyway.
Set `database.migrate: false` when the application user has no DDL rights, and apply the SQL
files by hand. See [docs/development/migrations.md](docs/development/migrations.md).

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
    eventTypePath: $.type                 # Required by the default topic template
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
  concurrency: 8                          # Messages published at the same time
  claimTimeoutMs: 300000                  # A claim older than this returns to pending
  pendingGaugeIntervalMs: 5000            # Minimum interval between pending count queries
  shutdownTimeoutMs: 30000                # Maximum wait for the in-flight messages

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
    topic: "{{ eventType }}"              # Outbox topic template for the relay
    eventTypePath: $.type                 # Extract the event type. The default topic template
                                          # needs it.
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

### Request Limits

QueueBox protects the inbox endpoints against a large body and against a request flood.

| Field | Default | Description |
|-------|---------|-------------|
| `inbox.maxBodyBytes` | `1048576` | Maximum accepted request body size in bytes. A larger body gets `413 Payload Too Large`. QueueBox rejects the body before it reads it. |
| `sources.<name>.rateLimit.requestsPerMinute` | none | Maximum number of requests per minute for one source. A request over the limit gets `429 Too Many Requests` with a `Retry-After` header. Omit the field to disable the rate limit for that source. |

```yaml
database:
  url: jdbc:postgresql://localhost:5432/queuebox
  username: queuebox
  password: ${DB_PASSWORD}

inbox:
  maxBodyBytes: 1048576                   # Reject a larger body with 413

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
    eventTypePath: $.type
    rateLimit:
      requestsPerMinute: 60               # The 61st request in a minute gets 429
```

### Outbound HTTP Limits

QueueBox bounds the error text of a failed publish and can refuse a destination on a private
address.

| Field | Default | Description |
|-------|---------|-------------|
| `http.maxErrorBodyBytes` | `2048` | Upper bound in bytes for the error body that a failed publish keeps. The publisher truncates the body and redacts every secret value before the text reaches a log or the `last_error` column. |
| `http.blockPrivateAddresses` | `false` | Set it to true to refuse a destination whose host resolves to a loopback, link-local, site-local, or unique-local address. QueueBox applies the check at startup. A host that does not resolve does not stop the startup. |

QueueBox validates every destination `baseUrl` at startup. The value must be an absolute `http` or
`https` URL with a host.

```yaml
database:
  url: jdbc:postgresql://localhost:5432/queuebox
  username: queuebox
  password: ${DB_PASSWORD}

http:
  maxErrorBodyBytes: 2048                 # Keep at most 2048 bytes of an error body
  blockPrivateAddresses: true             # Refuse a destination on a private address

destinations:
  my-api:
    type: http
    baseUrl: https://api.example.com
    path: /webhooks

routes:
  - topicPattern: "order.*"
    destination: my-api
```

### Admin Endpoint

The admin endpoint evaluates a caller-supplied JSONata expression. That is remote compute on the
message-processing host, so QueueBox disables the endpoint by default and needs authentication.

| Field | Default | Description |
|-------|---------|-------------|
| `admin.enabled` | `false` | Set it to true to register `/admin/transform/test`. The route does not exist while the value is false. |
| `admin.auth` | none | Credentials for the admin route. It uses the same schemes as `inbox.auth`: `bearer`, `api-key`, and `hmac`. A request without valid credentials gets 401. |
| `admin.insecure` | `false` | Set it to true to allow the admin route with no authentication. Use it for a local test only. |
| `admin.maxTransformTimeoutMs` | `1000` | Upper bound in milliseconds for the caller-supplied `timeoutMs`. QueueBox clamps a larger value to this bound. |
| `admin.maxPayloadBytes` | `65536` | Upper bound in bytes for the caller-supplied payload. A larger request gets 413. |

QueueBox refuses to start when `admin.enabled` is true, `admin.auth` is absent, and
`admin.insecure` is false.

```yaml
admin:
  enabled: true
  auth:
    type: bearer
    token: ${ADMIN_TOKEN}
  maxTransformTimeoutMs: 1000
  maxPayloadBytes: 65536
```

### Required Fields Reference

#### Core Required Fields

These fields have no defaults and must always be provided:

| Field | YAML Path | Environment Variable | Description |
|-------|-----------|---------------------|-------------|
| Database URL | `database.url` | `QUEUEBOX_DATABASE_URL` | JDBC connection string |
| Database Username | `database.username` | `QUEUEBOX_DATABASE_USERNAME` | Database user |
| Database Password | `database.password` | `QUEUEBOX_DATABASE_PASSWORD` | Database password |

#### Conditional Requirements

These fields are required only when configuring specific features:

**HTTP Destinations** (each destination in `destinations` with `type: http`):

| Field | Required | Default |
|-------|----------|---------|
| `baseUrl` | Yes | — |
| `path` | No | `/` |
| `timeoutMs` | No | `30000` |

**RabbitMQ Destinations** (each destination with `type: rabbitmq`):

| Field | Required | Default |
|-------|----------|---------|
| `url` | Yes | — |
| `exchange` | Yes | — |
| `exchangeType` | No | `topic` |

**Routes** (each entry in `routes`):

| Field | Required | Default |
|-------|----------|---------|
| `topicPattern` | Yes | — |
| `destination` | Yes | — (must reference existing destination) |

**HTTP Sources** (each source in `sources` with `type: http`):

| Field | Required | Default |
|-------|----------|---------|
| `path` | Yes | — |
| `idempotencyKeyPath` | Yes | — |
| `eventTypePath` | Yes, when `topic` uses `{{ eventType }}` | — |
| `aggregateIdPath` | No | — |
| `topic` | No | `{{ eventType }}` |

**RabbitMQ Sources** (each source with `type: rabbitmq`):

| Field | Required | Default |
|-------|----------|---------|
| `queueName` | Yes | — |
| `connectionUrl` | Yes | — |
| `idempotencyKeyPath` | No | `$.id` |
| `aggregateIdPath` | No | — |
| `prefetchCount` | No | `10` |
| `topic` | No | `{{ eventType }}` |

#### Authentication Requirements

When `auth` is specified, these fields become required based on auth type:

**Inbox Auth (sources):**

| Auth Type | Required Fields |
|-----------|-----------------|
| `bearer` | `token` |
| `api-key` | `key` |
| `hmac` | `secret` |

**Destination Auth:**

| Auth Type | Required Fields |
|-----------|-----------------|
| `oauth2` | `clientId`, `clientSecret`, `tokenUrl` |
| `basic` | `username` |
| `header` | `headerName`, `headerValue` |

#### Retention Requirements

When `retention.enabled: true`:

| Policy | Required Field |
|--------|----------------|
| `age` | `maxAge` (e.g., `7d`, `24h`) |
| `count` | `maxCount` (positive integer) |
| `disabled` | None |

#### Which timestamp the age policy uses

The two tables measure age from a different column. State the difference when you size a
retention window.

| Table | Column | Meaning |
|-------|--------|---------|
| `outbox` | `updated_at` | The moment of the last state change, which is the delivery or the dead-letter. |
| `inbox` | `created_at` | The moment of receipt. The inbox has no `updated_at` column. |

The test `postgres/src/test/kotlin/org/nxtspec/RetentionSemanticsTest.kt` is the source of truth.

The age policy deletes at most `batchSize` rows per statement, and it repeats until no eligible
row remains. The outbox age policy cleans the states `sent` and `dead`. The inbox age policy
cleans the states `processed` and `dead`.

### Environment Variables

QueueBox supports two ways to use environment variables:

#### 1. Substitution in YAML

Use `${VAR_NAME}` syntax with optional defaults inside your YAML config:

```yaml
database:
  url: ${DB_URL:-jdbc:postgresql://localhost:5432/queuebox}
  password: ${DB_PASSWORD}  # Required, no default
```

#### 2. Environment-Only Configuration (No YAML)

For container deployments, you can configure QueueBox entirely with environment variables using the `QUEUEBOX_` prefix — no YAML file needed.

**Naming convention:**
- Prefix all variables with `QUEUEBOX_`
- Use underscores to separate path segments (maps to dots in YAML)
- Use double underscore `__` for literal underscores in field names
- Use numeric segments for array indices

| Environment Variable | YAML Equivalent |
|---------------------|-----------------|
| `QUEUEBOX_DATABASE_URL` | `database.url` |
| `QUEUEBOX_DATABASE_USERNAME` | `database.username` |
| `QUEUEBOX_DATABASE_PASSWORD` | `database.password` |
| `QUEUEBOX_SERVER_HTTP_PORT` | `server.httpPort` |
| `QUEUEBOX_OUTBOX_POLL_INTERVAL_MS` | `outbox.pollIntervalMs` |
| `QUEUEBOX_ROUTES_0_TOPIC_PATTERN` | `routes[0].topicPattern` |
| `QUEUEBOX_ROUTES_0_DESTINATION` | `routes[0].destination` |
| `QUEUEBOX_DESTINATIONS_MY__API_BASE_URL` | `destinations.my_api.baseUrl` |

**Docker example:**

```bash
docker run -e QUEUEBOX_DATABASE_URL=jdbc:postgresql://db:5432/queuebox \
           -e QUEUEBOX_DATABASE_USERNAME=postgres \
           -e QUEUEBOX_DATABASE_PASSWORD=secret \
           -e QUEUEBOX_DESTINATIONS_WEBHOOK_TYPE=http \
           -e QUEUEBOX_DESTINATIONS_WEBHOOK_BASE_URL=https://api.example.com \
           -e QUEUEBOX_ROUTES_0_TOPIC_PATTERN="order.*" \
           -e QUEUEBOX_ROUTES_0_DESTINATION=webhook \
           -e QUEUEBOX_SOURCES_STRIPE_TYPE=http \
           -e QUEUEBOX_SOURCES_STRIPE_PATH=/stripe \
           -e QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCY_KEY_PATH="$.id" \
           queuebox
```

**Docker Compose example:**

```yaml
services:
  queuebox:
    image: queuebox
    environment:
      QUEUEBOX_DATABASE_URL: jdbc:postgresql://db:5432/queuebox
      QUEUEBOX_DATABASE_USERNAME: postgres
      QUEUEBOX_DATABASE_PASSWORD: secret
      QUEUEBOX_DESTINATIONS_WEBHOOK_TYPE: http
      QUEUEBOX_DESTINATIONS_WEBHOOK_BASE_URL: https://api.example.com
      QUEUEBOX_ROUTES_0_TOPIC_PATTERN: "order.*"
      QUEUEBOX_ROUTES_0_DESTINATION: webhook
      QUEUEBOX_SOURCES_STRIPE_TYPE: http
      QUEUEBOX_SOURCES_STRIPE_PATH: /stripe
      QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCY_KEY_PATH: "$.id"
```

**Minimum required variables:**
- `QUEUEBOX_DATABASE_URL`
- `QUEUEBOX_DATABASE_USERNAME`
- `QUEUEBOX_DATABASE_PASSWORD`

All other settings have defaults or are optional depending on your use case.

## HTTP API

### Health & Status

| Endpoint | Description |
|----------|-------------|
| `GET /` | Basic health check — returns "QueueBox is running!" |
| `GET /health/live` | Liveness. The process answer. It does no input or output. |
| `GET /health/ready` | Readiness. The database, the workers and each RabbitMQ connection. |
| `GET /health` | Alias of `/health/ready`. It stays for compatibility. |
| `GET /metrics` | Prometheus metrics |

#### Liveness, readiness and the management port

`GET /health/live` reports the process alone. It touches no dependency, so a slow database or a
broken database cannot fail it. Use it for a Kubernetes liveness probe.

`GET /health/ready` reports every dependency. It returns 200 when all components are up. It returns
503 when one component is down. The body names each component: `database`, `outbox-poller`,
`retention-service`, `inbox-relay`, and `rabbitmq.<source>` for each RabbitMQ source. Use it for a
Kubernetes readiness probe.

`GET /health` is an alias of `GET /health/ready`.

Set `server.managementPort` to move the operational endpoints to a separate port. The option is
optional and defaults to null. The port must differ from `server.httpPort`. When the port is set,
QueueBox serves `/metrics`, `/health/*` and `/admin` on that port only. The data port then returns
404 for those paths. Bind the management port to an internal network, because the metrics reveal
traffic volumes and destination names.

```yaml
server:
  httpPort: 8080
  managementPort: 9090
```

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
5. Message stored in `inbox` table with state `pending`
6. Return 200 OK
7. The relay forwards the row into the `outbox` table and marks it `processed`

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

## Security

Read [docs/operations/security.md](docs/operations/security.md) before you put QueueBox on a
network that you do not control. It covers the transport, the secrets, the admin endpoint, and
the request limits.

Three rules matter most.

1. **Terminate TLS in front of QueueBox.** QueueBox listens on plain HTTP and does not terminate
   TLS. The document holds a working ingress example.
2. **Point a credential at a file.** Every credential field accepts a `file:` reference, so an
   operator can mount a Kubernetes secret. QueueBox reads the file once, at startup.
3. **A credential never prints.** Every credential field carries the `Secret` type, whose
   `toString` returns a mask.

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
    eventTypePath: $.type
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

## The Inbox Relay

QueueBox forwards an inbox message onward. It never interprets the payload, because it does not
know the intent of the message.

The full path of an inbox message is: receive, deduplicate, transform, store, forward, route,
deliver.

1. **Receive.** An HTTP source or a RabbitMQ source accepts the message.
2. **Deduplicate.** The unique index on source and idempotency key rejects a repeat.
3. **Transform.** The source transform reshapes the payload, if one is configured.
4. **Store.** QueueBox writes the inbox row in state `pending`.
5. **Forward.** The relay claims the row, writes an outbox row, and marks the inbox row
   `processed`. Both writes run in one transaction.
6. **Route.** The outbox poller matches the topic against the routes.
7. **Deliver.** The publisher sends the message to the destination.

**Field mapping from the inbox row to the outbox row:**

| Outbox field | Source |
|--------------|--------|
| `topic` | The rendered `sources.<name>.topic` template |
| `key` | The inbox `aggregate_id` |
| `payload` | The stored inbox payload |
| `headers` | `x-inbox-id`, `x-source`, and `x-idempotency-key` |

The topic template supports `{{ source }}` and `{{ eventType }}`. The default is
`{{ eventType }}`. The relay marks the message `dead` when the template renders empty.

```yaml
inbox:
  relay:
    enabled: true
    pollIntervalMs: 100
    batchSize: 100
    claimTimeoutMs: 300000

sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
    eventTypePath: $.type
    aggregateIdPath: $.customer
    topic: "{{ source }}.{{ eventType }}"
```

**Guarantee.** Forwarding is at least once. If the transaction fails, the inbox row stays in
state `processing`, and the reclaim step returns it to `pending` after `claimTimeoutMs`.

## Aggregate Ordering

Configure `aggregateIdPath` to extract the aggregate identifier:

```yaml
sources:
  orders:
    type: http
    path: /orders
    idempotencyKeyPath: $.eventId
    eventTypePath: $.type
    aggregateIdPath: $.orderId
```

**What the shipped code does:**

- The claim query excludes an aggregate that already has a message in state `processing`.
- The claim keeps the oldest claimed message per aggregate and returns the rest to `pending`.
- A message without an aggregate identifier is independent, and it carries no ordering
  guarantee.
- Different aggregates are forwarded in parallel.

**Guarantee.** At most one message per aggregate identifier is in state `processing` at any
time, across every replica. The relay forwards the messages of one aggregate in creation order.

Ordering after the forward step is not guaranteed. The outbox poller and the destination decide
the final delivery order.

The tests `postgres/src/test/kotlin/org/nxtspec/InboxRepositoryConcurrencyTest.kt` and
`sqlserver/src/test/kotlin/org/nxtspec/SqlServerInboxRepositoryConcurrencyTest.kt` are the
source of truth for the claim guarantee.

## Routing Key Templates

For RabbitMQ destinations, you can dynamically construct routing keys from message data:

```yaml
routes:
  - topicPattern: "order.*"
    destination: events-exchange
    routingKeyTemplate: "{{ payload.region }}.{{ payload.priority }}.{{ topic }}"
    routingKeyMissingFieldDefault: "default"
```

**Template variables:**
- `{{ topic }}` — The message topic.
- `{{ payload.fieldName }}` — A field from the message payload.
- `{{ data.fieldName }}` — An alias for `payload.fieldName`.
- Nested fields: `{{ payload.customer.region }}` or `{{ data.customer.region }}`.

Any other placeholder, including a bare field name such as `{{ region }}`, renders as the
`routingKeyMissingFieldDefault` value. If a field is missing, the same default applies. The
default is an empty string if `routingKeyMissingFieldDefault` is not configured.

The test
`outbox-service/src/test/kotlin/org/nxtspec/RoutingKeyTemplateContractTest.kt` is the source of
truth for the supported placeholder forms.

**Ordering under concurrency.** The poller claims a batch in order, oldest first, and then
publishes up to `outbox.concurrency` messages at the same time. Two messages of one batch can
therefore arrive at the destination out of order. Set `outbox.concurrency: 1` when a destination
needs strict order.

A RabbitMQ destination is the exception. It holds one confirmed channel, and one publish at a
time uses it, so `outbox.concurrency` raises throughput only across different destinations. An
HTTP destination has no such limit.

**Precedence.** The route `routingKeyTemplate` wins. QueueBox renders it, and the RabbitMQ
publisher uses the result. A RabbitMQ destination also has its own `routingKeyTemplate`, which
supports `{{ topic }}` only. That destination template applies only when the matched route sets
no `routingKeyTemplate`.

**RabbitMQ throughput.** The publisher awaits one broker confirm per message. A measured run gave
1038 messages per second for 1000 messages on one destination. The test
`rabbitmq/src/test/kotlin/RabbitPublisherThroughputTest.kt` produced this figure on a developer
laptop, with the broker in a local container.

## Metrics

QueueBox exposes Prometheus metrics at `/metrics`:

The table lists every metric that QueueBox emits. Every label set is bounded. A metric never
carries a message identifier, a raw error string, or a raw HTTP status code as a label.

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `queuebox_outbox_messages_total` | counter | `status` = sent, failed, dead | Outbox messages by final status. |
| `queuebox_outbox_messages_pending` | gauge | none | Messages in state `pending`. |
| `queuebox_outbox_processing_duration_seconds` | timer | none | Time to process one outbox message. |
| `queuebox_outbox_publish_duration_seconds` | timer | `destination_type` = http, rabbitmq | Time to publish to a destination type. |
| `queuebox_outbox_messages_reclaimed_total` | counter | none | Messages returned to `pending` after a stale claim. |
| `queuebox_outbox_process_errors_total` | counter | none | Errors that stopped the processing of one message. |
| `queuebox_outbox_destination_messages_total` | counter | `destination`, `outcome` = success, failure | Publish outcomes per configured destination. |
| `queuebox_outbox_queue_depth` | gauge | `destination` | Messages that wait for a publish to the destination. |
| `queuebox_transform_failures_total` | counter | `strategy` = skip, fail, dead | Transform failures by error strategy. |
| `queuebox_http_publish_responses_total` | counter | `status_class` = 1xx, 2xx, 3xx, 4xx, 5xx, other | HTTP publish responses by status class. |
| `queuebox_inbox_messages_total` | counter | `status` = new, duplicate, forwarded | Inbox messages by status. |
| `queuebox_inbox_relay_errors_total` | counter | none | Errors of the inbox relay. |
| `queuebox_inbox_rejections_total` | counter | `reason` = extraction_failed, transform_failed, storage_failed | Inbox messages that QueueBox rejected. |
| `queuebox_cleanup_messages_deleted_total` | counter | `table` = outbox, inbox | Messages that the retention cleanup deleted. |
| `queuebox_cleanup_duration_seconds` | timer | `table` | Duration of one cleanup run. |
| `queuebox_cleanup_last_run_timestamp` | gauge | `table` | Unix time of the last cleanup run. |
| `queuebox_uptime_seconds` | gauge | none | Time since the application started. |
| `queuebox_info` | gauge | `version` | Application information. The version comes from the build. |

The Prometheus exporter removes the `_info` suffix, so `queuebox_info` appears in the scrape as
`queuebox`.

Micrometer also registers the JVM metrics and the HikariCP pool metrics on the same endpoint.
Those names start with `jvm_` and `hikaricp_`.

## Database Schema

QueueBox creates two tables:

**outbox:**
```
id              UUID PRIMARY KEY
topic           VARCHAR(255)
key             VARCHAR(255)        -- Optional partition/ordering key
payload         JSONB
headers         JSONB
state           VARCHAR(20)         -- 'pending', 'processing', 'sent', 'dead'
attempt         INTEGER
max_attempts    INTEGER
scheduled_at    TIMESTAMP
created_at      TIMESTAMP
updated_at      TIMESTAMP
claimed_at      TIMESTAMP           -- When the poller claimed the row
```

**inbox:**
```
id              UUID PRIMARY KEY
source          VARCHAR(255)
idempotency_key VARCHAR(255)        -- Unique per source
aggregate_id    VARCHAR(255)
event_type      VARCHAR(255)
payload         JSONB
state           VARCHAR(20)         -- 'pending', 'processing', 'processed', 'dead'
created_at      TIMESTAMP
processed_at    TIMESTAMP
claimed_at      TIMESTAMP           -- When the relay claimed the row
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

## Operations

- [Operations runbook](docs/operations/runbook.md) — inspect and replay dead-lettered messages,
  react to a growing pending gauge, size the pool and the batch, and diagnose a slow destination.
- [Dead letters](docs/operations/dead-letter.md) — the SQL to list a dead message and to requeue one.

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

### Not in 1.0

QueueBox 1.0 does not ship these features. The list is a statement of intent, not a promise of a
date.

| Feature | Target | Note |
|---------|--------|------|
| OpenTelemetry tracing | 1.1 | 1.0 correlates a message with the `X-Correlation-Id` header, the `correlation_id` column, and the `correlationId` field of every log line. That covers the debugging path. A span tree does not. See [docs/operations/runbook.md](docs/operations/runbook.md). |
| Message replay from the dead-letter state | 1.1 | 1.0 documents the SQL. See [docs/operations/dead-letter.md](docs/operations/dead-letter.md). |
| Kafka destination | 1.2 | |
| Rate limit per destination | 1.2 | 1.0 rate limits an inbox source. |
| Admin user interface | not scheduled | |
| Kubernetes Helm chart | not scheduled | The deployment examples in [docs/operations/security.md](docs/operations/security.md) cover the same ground. |

### How to read this table

A row with a version number is work that a maintainer intends to do. A row that says "not
scheduled" is work that nobody has committed to. Do not plan a deployment around a row of the
second kind.

## License

[LICENSE](LICENSE)
