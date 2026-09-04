# Getting started

This document holds the full quick start. `README.md` holds a shorter one.

## Quick Start

### Requirements

- Docker, to run the published image.
- PostgreSQL 14, 15 or 16, or SQL Server 2019 or 2022.
- A Java Development Kit, version 21, only to build from the source or to run without a container.
  QueueBox targets Java 21, the long term support release. Java 22 and Java 23 also compile the
  code. Java 20 and earlier do not.

### Run the published image

QueueBox publishes a multi-architecture image for `linux/amd64` and `linux/arm64` to GitHub
Container Registry.

```bash
docker pull ghcr.io/alternayte/queuebox:latest
```

Run the image against an existing database:

```bash
docker run --rm -p 8080:8080 \
  -e QUEUEBOX_DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/queuebox \
  -e QUEUEBOX_DATABASE_USERNAME=queuebox \
  -e QUEUEBOX_DATABASE_PASSWORD=secret \
  ghcr.io/alternayte/queuebox:latest
```

The service listens on `http://localhost:8080`.

Three tag forms exist. Pin the exact version in production.

| Tag | Meaning |
|---|---|
| `ghcr.io/alternayte/queuebox:1.2.3` | The exact release. |
| `ghcr.io/alternayte/queuebox:1.2` | The newest patch of that minor release. |
| `ghcr.io/alternayte/queuebox:latest` | The newest release. |

### Using Docker Compose

```bash
git clone https://github.com/alternayte/queuebox.git
cd queuebox
docker compose -f docker-compose.yml --env-file .env.example up -d --build
```

This starts QueueBox with PostgreSQL. The service is available at `http://localhost:8080`.

`-f docker-compose.yml` selects the shipped stack. Without it, Docker Compose also reads
`docker-compose.override.yml`, which holds the development loop. That loop mounts the source tree
and rebuilds on every change, which a first run does not need.

`docker-compose.yml` mounts `examples/queuebox.yml` at `/etc/queuebox/queuebox.yml`. Edit that
file and restart the container. You do not rebuild the image.

To include RabbitMQ:

```bash
docker compose --profile rabbitmq up -d
```

### Manual Setup

1. Create a PostgreSQL database:
```sql
CREATE DATABASE queuebox;
```

QueueBox creates its own tables. At startup it applies the bundled migrations with Flyway.
Set `database.migrate: false` when the application user has no DDL rights, and apply the SQL
files by hand. See [development/migrations.md](development/migrations.md).

2. Write the configuration file. Never edit
   `config/src/main/resources/queuebox.yml`, because that file is packaged into the image and a
   change to it needs a rebuild. Copy `examples/queuebox.yml` instead.

```bash
sudo mkdir -p /etc/queuebox
sudo cp examples/queuebox.yml /etc/queuebox/queuebox.yml
```

   `QUEUEBOX_CONFIG_FILE` names another path when `/etc/queuebox/queuebox.yml` does not suit.

3. Set the database variables. An environment variable wins over every file.

```bash
export QUEUEBOX_DATABASE_URL=jdbc:postgresql://localhost:5432/queuebox
export QUEUEBOX_DATABASE_USERNAME=postgres
export QUEUEBOX_DATABASE_PASSWORD=secret
```

   The name after the `QUEUEBOX_` prefix is the configuration path in upper case. One underscore
   separates one level from the next level. The loader turns every single underscore into a level
   separator, and it reassembles no camelCase name, so a leaf name of more than one word carries no
   underscore inside it. `QUEUEBOX_DATABASE_URL` sets `database.url`, and
   `QUEUEBOX_SERVER_HTTPPORT` sets `server.httpPort`. An extra underscore inside a leaf name makes
   a path that does not exist, and the variable then sets nothing.
   [configuration.md](configuration.md) holds the full rule.

4. Start the container, or build from the source and run `./gradlew run`. See
   [development/building.md](development/building.md). QueueBox applies the bundled
   Flyway migrations at startup, so step 1 is the only schema work you do by hand.

5. Confirm the instance is ready.

```bash
curl http://localhost:8080/health
```

   A ready instance answers `200` with `{"status":"healthy",...}`.

### Building the Docker Image

To build the image yourself, see [development/building.md](development/building.md). That
document holds the local build, the multi-architecture build and the release procedure.

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

QueueBox builds the path of an HTTP source from `inbox.basePath` and the `path` of that source.
The path does not hold the source name. `inbox.basePath` defaults to `/inbox`, so a source with
`path: /stripe` answers at `/inbox/stripe`.

```yaml
inbox:
  basePath: /inbox

sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
    eventTypePath: $.type
```

```bash
# Send a message to the endpoint of the 'stripe' source
curl -X POST http://localhost:8080/inbox/stripe \
  -H "Content-Type: application/json" \
  -d '{"id": "evt_123", "type": "payment.completed"}'
```

The route returns one of these codes. `docs/adr/0002-inbox-accept-returns-202.md` holds the
decision, and `inbox-service/src/test/kotlin/org/nxtspec/InboxRoutesTest.kt` is the source of
truth.

| Code | Meaning |
|------|---------|
| 202 | The message is new and stored. |
| 200 | The message is a duplicate of a stored message. |
| 400 | The body is not JSON, or the idempotency key path found no value. |
| 401 | The source requires authentication, and the request failed the check. |
| 413 | The body is larger than the configured cap. |
| 422 | The transform rejected the payload. |
| 429 | The request went over the per-source rate limit. |
| 500 | The storage layer failed. |

An unconfigured path returns 404, because QueueBox registers no route for it.

### Admin Endpoints

The admin endpoint runs a caller-supplied JSONata expression on the host that processes the
messages. QueueBox therefore does not register the route until you enable it. `admin.enabled`
defaults to false. Authentication is also mandatory. The start fails when `admin.enabled` is true
and `admin.auth` is absent, unless you set `admin.insecure: true` for a local test.

```yaml
admin:
  enabled: true
  auth:
    type: bearer
    token: the-admin-token
```

With that configuration in place, send the request with the credentials:

```bash
# Test a JSONata transform expression
curl -X POST http://localhost:8080/admin/transform/test \
  -H "Authorization: Bearer the-admin-token" \
  -H "Content-Type: application/json" \
  -d '{
    "expression": "{ \"total\": items.(price * qty) ~> $sum() }",
    "payload": {"items": [{"price": 10, "qty": 2}, {"price": 5, "qty": 3}]},
    "mockTopic": "order.created"
  }'
```

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

