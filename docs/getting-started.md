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
files by hand. See [docs/development/migrations.md](docs/development/migrations.md).

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

   The name after the `QUEUEBOX_` prefix is the configuration path in upper case, with an
   underscore for each level. `QUEUEBOX_DATABASE_URL` sets `database.url`.

4. Start the container, or build from the source and run `./gradlew run`. See
   [docs/development/building.md](docs/development/building.md). QueueBox applies the bundled
   Flyway migrations at startup, so step 1 is the only schema work you do by hand.

5. Confirm the instance is ready.

```bash
curl http://localhost:8080/health
```

   A ready instance answers `200` with `{"status":"healthy",...}`.

### Building the Docker Image

To build the image yourself, see [docs/development/building.md](docs/development/building.md). That
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

