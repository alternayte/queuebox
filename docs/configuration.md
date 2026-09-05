# Configuration reference

This document holds the full QueueBox configuration reference. `README.md` links here.

QueueBox reads the configuration from three sources. The first source that holds a key wins.

1. An environment variable that starts with `QUEUEBOX_`. The name after the prefix is the
   configuration path in upper case. `QUEUEBOX_DATABASE_URL` sets `database.url`.
2. One YAML file. QueueBox reads the file that `QUEUEBOX_CONFIG_FILE` names. When the environment
   names no file, QueueBox reads `/etc/queuebox/queuebox.yml`.
3. The YAML resource packaged in the image.

Source 2 and source 3 are alternatives, not layers. An external file **replaces** the packaged
resource. The packaged resource is the fallback for a deployment that supplies no external file.
An external file must therefore be a **complete** configuration. A file that declares one
destination gets that destination and no other. It does not inherit a destination, a source or a
route from the packaged resource. See finding F-076 in `hardening-doc.md`.

An environment variable still wins over the file that QueueBox reads.

`examples/queuebox.yml` is a complete example, and `docker-compose.yml` mounts it at the default
external path.

## Configuration

QueueBox uses YAML configuration with environment variable substitution.

Never edit `config/src/main/resources/queuebox.yml`. That file is packaged into the image, so a
change to it needs a rebuild. Write the external file instead. Copy `examples/queuebox.yml` as the
start point, because the external file replaces the packaged resource in full.

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
  managementPort: 9090                    # Optional. Moves /health, /metrics AND /admin to their own
                                          # port, so an ingress can keep them off the data port.
                                          # Omit it and both stay on httpPort.

database:
  type: postgresql                        # 'postgresql' or 'sqlserver'
  url: jdbc:postgresql://localhost:5432/queuebox
  username: queuebox
  password: ${DB_PASSWORD}
  poolSize: 10                            # Connection pool size
  connectionTimeoutMs: 30000              # Connection timeout
  migrate: true                           # Apply the bundled Flyway migrations at startup.
                                          # Set it to false with a custom schema. See below.
  startupTimeoutMs: 60000                 # How long QueueBox waits for the database at startup
                                          # before it gives up and exits.
  outboxTableName: outbox                 # Custom table name (default: outbox)
  inboxTableName: inbox                   # Custom table name (default: inbox)

outbox:
  pollIntervalMs: 100                     # How often to check for pending messages
  batchSize: 100                          # Messages per poll cycle
  retryBaseDelayMs: 1000                  # Base delay for exponential backoff
  maxAttempts: 5                          # Default delivery ceiling. The row can override it.
  concurrency: 8                          # Messages published at the same time
  claimTimeoutMs: 300000                  # A claim older than this returns to pending
  pendingGaugeIntervalMs: 5000            # Minimum interval between pending count queries
  shutdownTimeoutMs: 30000                # Maximum wait for the in-flight messages

inbox:
  basePath: /inbox                        # Base path for inbox HTTP endpoints
  relay:
    enabled: true                         # The relay moves a stored inbox row into the outbox.
                                          # Turn it off and the inbox becomes a write-only log.
    pollIntervalMs: 100                   # How often the relay looks for a pending row
    batchSize: 100                        # Rows per relay cycle
    claimTimeoutMs: 300000                # Visibility timeout. A claim older than this returns
                                          # to state 'pending'. See F-006.

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
      timeoutMs: 100                      # Maximum run time of one expression
      maxDepth: 100                       # Maximum recursion depth of one expression. It bounds
                                          # a hostile or a runaway expression.
      onError: Fail                       # 'Fail', 'Skip' or 'Dead'. Write the exact case.

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
      onError: Fail                       # 'Fail', 'Skip', or 'Dead'

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
    idempotencyKeyPath: $.messageId       # Set this. See the note below.
    aggregateIdPath: $.orderId            # Optional: for ordered processing
    eventTypePath: $.type                 # Optional: extract the event type from the body
    prefetchCount: 10
    topic: "{{ source }}"                 # Outbox topic template. The default needs no event type.

# Automatic cleanup of old messages
retention:
  enabled: true
  outbox:
    policy: AGE                           # 'AGE', 'COUNT', or 'DISABLED'
    maxAge: 7d                            # Keep messages younger than 7 days
    cleanupInterval: 1h                   # Run cleanup every hour
    batchSize: 1000                       # Delete in batches
  inbox:
    policy: AGE                           # The inbox accepts 'AGE' or 'DISABLED' only
    maxAge: 30d
    cleanupInterval: 6h
    batchSize: 1000
```

**Always give an AMQP source a key.** QueueBox reads the idempotency key from the
`x-idempotency-key` header, then from `idempotencyKeyPath`, then from the AMQP `messageId`
property. When all three give nothing, QueueBox falls back to a SHA-256 digest of the body. A
redelivery then deduplicates, which is correct, but two DISTINCT events that carry an identical
body also deduplicate, and the second event is NOT forwarded.
[message-flow.md](message-flow.md) states the rule in full.

**The event type of an AMQP source.** QueueBox reads the event type from `eventTypePath` in the
message body first. When that gives nothing, QueueBox reads the AMQP header `x-event-type`. The
header name is fixed. A message with no event type renders `{{ eventType }}` as an empty string,
and the relay marks such a message dead. The default topic template of an AMQP source is therefore
`{{ source }}`, which every message can render. To use `{{ eventType }}` in the template, set
`eventTypePath`, or set `eventTypeFromHeader: true` to declare that every publisher of the queue
sets the `x-event-type` header. QueueBox refuses the start when the template uses `{{ eventType }}`
and neither field is set.

```yaml
sources:
  orders-queue:
    type: rabbitmq
    queueName: incoming-orders
    connectionUrl: amqp://localhost:5672
    idempotencyKeyPath: $.messageId
    eventTypePath: $.type                 # The body carries the event type
    topic: "{{ source }}.{{ eventType }}"

  audit-queue:
    type: rabbitmq
    queueName: audit
    connectionUrl: amqp://localhost:5672
    idempotencyKeyPath: $.messageId
    eventTypeFromHeader: true             # Every publisher sets the x-event-type AMQP header
    topic: "{{ eventType }}"
```

### How QueueBox picks the dead-letter ceiling

QueueBox compares the `attempt` column of a row against the `max_attempts` column of the SAME
row. It never reads `outbox.maxAttempts` at delivery time. The precedence is therefore:

1. **The row wins.** An application that inserts an outbox row can set `max_attempts` on that
   row. That value is the ceiling for that one message. Use it to give a slow destination more
   attempts than the rest of the system.
2. **The configured value is the default.** QueueBox writes `outbox.maxAttempts` into
   `max_attempts` for every row that QueueBox itself creates. The inbox relay creates such rows.
   A message that arrives on an inbox endpoint therefore gets the configured ceiling.
3. **The column default is the last resort.** A row that neither the application nor QueueBox
   gives a value takes the schema default of `5`.

`inbox.relay.maxAttempts` overrides step 2 for relayed rows only. Leave it unset, and the relay
uses `outbox.maxAttempts`.

```yaml
outbox:
  maxAttempts: 5
inbox:
  relay:
    maxAttempts: 10                       # Optional. Relayed rows only. Default: outbox.maxAttempts
```

### Request Limits

QueueBox protects the inbox endpoints against a large body and against a request flood.

| Field | Default | Description |
|-------|---------|-------------|
| `inbox.maxBodyBytes` | `1048576` | Maximum accepted request body size in bytes. A larger body gets `413 Payload Too Large`. A request that declares a `Content-Length` is rejected before QueueBox reads the body. A chunked request declares no length, so the inbox route counts the bytes as it reads them and rejects the request when the count passes the cap. |
| `sources.<name>.rateLimit.requestsPerMinute` | none | Maximum number of requests per minute for one source. It applies to an HTTP source only, because the limit guards an HTTP route. QueueBox accepts the field on a `rabbitmq` source and ignores it there. A request over the limit gets `429 Too Many Requests` with a `Retry-After` header. Omit the field to disable the rate limit for that source. |

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
| `eventTypePath` | No | — |
| `eventTypeFromHeader` | No | `false` |
| `prefetchCount` | No | `10` |
| `topic` | No | `{{ source }}` |

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
| `basic` | `username`, `password` |
| `header` | `headerName`, `headerValue` |

#### Retention Requirements

When `retention.enabled: true`:

| Policy | Required Field | Accepted for |
|--------|----------------|--------------|
| `AGE` | `maxAge`, for example `7d` or `24h` | `outbox` and `inbox` |
| `COUNT` | `maxCount`, a positive integer | `outbox` only |
| `DISABLED` | None | `outbox` and `inbox` |

**The inbox rejects the count policy.** The inbox repository holds no count-based delete, so the
policy deletes nothing. QueueBox stops at startup with an error that names
`retention.inbox.policy`. Use `AGE` or `DISABLED` for the inbox.

**The count ceiling counts per state, not per table.** The outbox count policy keeps `maxCount`
rows of the state `sent` AND `maxCount` rows of the state `dead`. `maxCount: 100000` therefore
keeps up to 200000 rows. The two states are counted apart on purpose. A flood of delivered
messages then cannot evict the dead messages that an operator still has to inspect. Halve the
number if you size the table against one total. The test
`outbox-service/src/test/kotlin/org/nxtspec/RetentionServiceTest.kt` pins the rule.

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

- Prefix every variable with `QUEUEBOX_`.
- Write one underscore between one level of the path and the next level.
- Write the leaf name as one word, with no underscore inside it. The loader turns **every** single
  underscore into a level separator, and it reassembles no camelCase name. `outbox.pollIntervalMs`
  is therefore `QUEUEBOX_OUTBOX_POLLINTERVALMS`. An extra underscore inside the leaf name makes the
  path `outbox.poll.interval.ms`, which does not exist, so that variable sets nothing and the start
  reports no error.
- Write a double underscore `__` for a literal underscore inside a map key.
- Write a numeric segment for a list index.

`EnvConfigLoader.envKeyToYamlPath` holds the rule, and every validation error message prints the
name through `EnvConfigLoader.yamlPathToEnvKey`. The test `DocumentedExamplesTest` fails when a
document names a variable that binds nothing.

| Environment Variable | YAML Equivalent |
|---------------------|-----------------|
| `QUEUEBOX_DATABASE_URL` | `database.url` |
| `QUEUEBOX_DATABASE_USERNAME` | `database.username` |
| `QUEUEBOX_DATABASE_PASSWORD` | `database.password` |
| `QUEUEBOX_SERVER_HTTPPORT` | `server.httpPort` |
| `QUEUEBOX_OUTBOX_POLLINTERVALMS` | `outbox.pollIntervalMs` |
| `QUEUEBOX_ROUTES_0_TOPICPATTERN` | `routes[0].topicPattern` |
| `QUEUEBOX_ROUTES_0_DESTINATION` | `routes[0].destination` |
| `QUEUEBOX_DESTINATIONS_MY__API_BASEURL` | `destinations.my_api.baseUrl` |

**Docker example:**

```bash
docker run -e QUEUEBOX_DATABASE_URL=jdbc:postgresql://db:5432/queuebox \
           -e QUEUEBOX_DATABASE_USERNAME=postgres \
           -e QUEUEBOX_DATABASE_PASSWORD=secret \
           -e QUEUEBOX_DESTINATIONS_WEBHOOK_TYPE=http \
           -e QUEUEBOX_DESTINATIONS_WEBHOOK_BASEURL=https://api.example.com \
           -e QUEUEBOX_ROUTES_0_TOPICPATTERN="order.*" \
           -e QUEUEBOX_ROUTES_0_DESTINATION=webhook \
           -e QUEUEBOX_SOURCES_STRIPE_TYPE=http \
           -e QUEUEBOX_SOURCES_STRIPE_PATH=/stripe \
           -e QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH="$.id" \
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
      QUEUEBOX_DESTINATIONS_WEBHOOK_BASEURL: https://api.example.com
      QUEUEBOX_ROUTES_0_TOPICPATTERN: "order.*"
      QUEUEBOX_ROUTES_0_DESTINATION: webhook
      QUEUEBOX_SOURCES_STRIPE_TYPE: http
      QUEUEBOX_SOURCES_STRIPE_PATH: /stripe
      QUEUEBOX_SOURCES_STRIPE_IDEMPOTENCYKEYPATH: "$.id"
```

**Minimum required variables:**
- `QUEUEBOX_DATABASE_URL`
- `QUEUEBOX_DATABASE_USERNAME`
- `QUEUEBOX_DATABASE_PASSWORD`

All other settings have defaults or are optional depending on your use case.

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
  migrate: false                          # Mandatory with a custom schema. See the warning below.
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

**Set `database.migrate` to false with a custom schema.** `database.migrate` defaults to true.
The bundled migration files name the default table and column names, so they cannot create a
renamed schema. QueueBox therefore refuses to start when the configuration renames a table or a
column and `database.migrate` stays true. The startup error names each renamed key. Apply your own
schema first. [development/migrations.md](development/migrations.md) holds the SQL of the default
schema.

**Available column mappings:**

Each table below is the complete set. A key that you omit keeps the default name.

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
| `claimedAt` | claimed_at | Time of the claim. The reclaim step reads it. |
| `lastError` | last_error | Reason of the last failed delivery |

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
| `claimedAt` | claimed_at | Time of the claim. The reclaim step reads it. |
| `correlationId` | correlation_id | Identifier that follows the message through every log line |

