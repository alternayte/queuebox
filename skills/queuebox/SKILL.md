---
name: queuebox
description: Write correct application code for QueueBox, the transactional outbox and idempotent inbox service on Postgres or SQL Server. Use when code inserts outbox rows, consumes the QueueBox inbox with a pull client (Go, TypeScript, C#), receives messages that QueueBox delivers, or edits queuebox.yml sources, destinations, routes or transforms.
---

# QueueBox

QueueBox is a service next to the application database. The application inserts a row into the `outbox` table in its own transaction; QueueBox delivers the row to HTTP, RabbitMQ, Kafka or NATS. Webhooks and brokers feed the `inbox` table, which deduplicates on `(source, idempotency_key)`. Delivery is at least once everywhere.

Docs: https://queuebox-docs.pages.dev/llms-full.txt (all pages), https://queuebox-docs.pages.dev/llms.txt (index). Each page is also Markdown at its URL plus `.md`.

## Write an outbox row

- Required: `topic` (at most 255 characters, dotted lower case, for example `order.created`) and `payload` (a JSON object). Every other column has a default or accepts null.
- Optional: `id` (default a new UUID), `key`, `headers` (JSON object, default `{}`), `aggregate_type`, `scheduled_at` (QueueBox claims the row only after it passes), `max_attempts` (default 5; the row value wins over `outbox.maxAttempts`).
- `key`: the rows of one non-empty key arrive in insert order, one in flight at a time. A row that waits for a retry holds back the later rows of its key until it is sent or dead. A null or empty key has no order. Two keys have no order between them.
- `headers`: each entry becomes a delivery header. Omit the column for no header; an explicit `NULL` fails the insert.
- The insert runs in the same transaction, on the same connection, as the business write.
- The topic must match a route `topicPattern`, or the row goes `dead`.

Postgres (`payload` and `headers` are `JSONB`):

```sql
BEGIN;
INSERT INTO orders (id, customer_id, amount) VALUES ($1, $2, $3);
INSERT INTO outbox (topic, key, payload, headers, aggregate_type)
VALUES ('order.created', 'cust-42', '{"orderId":"o-1","amount":99.99}'::jsonb, '{"X-Tenant":"acme"}'::jsonb, 'order');
COMMIT;
```

SQL Server (`key` is a reserved word, so write `[key]`; JSON columns are `NVARCHAR(MAX)`):

```sql
BEGIN TRANSACTION;
INSERT INTO orders (id, customer_id, amount) VALUES (@id, @customer, @amount);
INSERT INTO outbox (topic, [key], payload, headers, aggregate_type)
VALUES (N'order.created', N'cust-42', N'{"orderId":"o-1","amount":99.99}', N'{"X-Tenant":"acme"}', N'order');
COMMIT TRANSACTION;
```

With an ORM, map `headers` and `aggregate_type` on the entity with explicit column names. An unmapped property never writes its column. Do not generate a migration for the outbox table; QueueBox owns and migrates it.

## queuebox.yml

- `database`: `type` (`postgresql` or `sqlserver`), `url` (JDBC), `username`, `password`.
- `destinations`: a map of name to `type: http | rabbitmq | kafka | nats` and its settings.
- `routes`: a list, first match wins. `topicPattern` is anchored: `*` matches one dot-free segment, `**` matches any text. Optional `routingKeyTemplate`, `transform`.
- `sources`: a map of name to an inbox source (`http`, `rabbitmq`, `kafka`, `nats`). `idempotencyKeyPath` is a JSONPath. `consumption` is `push` (default) or `pull`. A push source needs a `topic` template (default `{{ eventType }}` for HTTP, which needs `eventTypePath`) and a route for that topic. A pull source needs no topic, route or destination.
- `transform`: JSONata on a route, destination or source: `expression`, `timeoutMs` (100), `maxDepth` (100), `onError` spelled exactly `Fail`, `Skip` or `Dead`.
- A `QUEUEBOX_*` environment variable wins over the file, for example `QUEUEBOX_DATABASE_PASSWORD`. An external file replaces the packaged config; it does not overlay it, so write it complete.

```yaml
database:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/app
  username: queuebox
  password: change-me
destinations:
  orders-api:
    type: http
    baseUrl: https://orders.example.com
    path: /events
routes:
  - topicPattern: "order.*"
    destination: orders-api
    transform:
      expression: '{ "id": orderId, "total": amount }'
sources:
  stripe:
    type: http
    path: /stripe
    idempotencyKeyPath: $.id
    eventTypePath: $.type
    topic: "stripe.{{ eventType }}"
  orders:
    type: http
    path: /orders
    idempotencyKeyPath: $.id
    consumption: pull
```

An HTTP source accepts `POST /inbox/<path>` (the prefix is `inbox.basePath`).

## Consume the inbox

- Push: the inbox relay copies each row into the outbox with the source topic, and a route delivers it. The application consumes at the destination. Do not poll the inbox table.
- Pull (`consumption: pull`): a worker claims rows of one source, runs the handler in a transaction, then completes, retries or dead-letters each row. Use a client library:

| Language | Package | Core call |
|---|---|---|
| Go | `github.com/alternayte/queuebox/clients/go` (package `queuebox`) | `queuebox.NewInboxWorker(db, queuebox.Options{Source: "orders"})`, then `worker.Run(ctx, func(ctx, message, tx *sql.Tx) error)` |
| TypeScript | `@alternayte/queuebox-inbox` | `new InboxWorker(fromPg(pool), { source: "orders" })` or `fromMssql(mssql, pool)`, then `worker.run(async (message, tx, signal) => ...)` |
| C# | `QueueBox.Inbox` (hosted service: `QueueBox.Inbox.DependencyInjection`) | `new InboxWorker(InboxConnections.From(dataSource), new InboxOptions { Source = "orders" })`, then `worker.RunAsync(async (message, transaction, token) => ...)`, or `services.AddQueueBoxInbox(...)` |

Handler rules: write every change through the transaction the handler receives. Do not commit or roll back; the library does. Return an error or throw to fail the message. Honour the context, signal or token; it fires when the lease is lost. On SQL Server set `Dialect`/`dialect` to SQL Server and give the driver a request or command timeout of at least 30 seconds. In C# with EF Core, build the context with `InboxDbContextFactory.CreateOn(transaction, ...)`. Defaults: `BatchSize` 10, `LeaseMs` 30000, `MaxConcurrency` 1. A retry policy returns dead-letter or retry-after.

Without a library, use the statements in `examples/pull/sql/<dialect>/` (`claim`, `renew`, `complete`, `retry`, `dead`) with bound parameters. Renew every third of the lease. Each update must affect exactly one row; zero means the lease is lost, so stop and roll back.

## Idempotent consumers

- An HTTP destination receives `X-Message-Id` (the outbox row `id`, stable across retries), `X-Topic`, `X-Attempt` (from 0), `X-Message-Key` (when set) and the row `headers`.
- Deduplicate direct outbox traffic on `X-Message-Id`. Deduplicate relay traffic on `x-idempotency-key`: a replayed inbox row gets a new outbox `id`.
- Store the message identifier and the business effect in one transaction. Answer 2xx only after the commit. A 2xx, including 202, ends delivery for good; any other status retries.
- A pull handler deduplicates external side effects on `(source, idempotency_key)`. Two sources can send the same key.

## Do not

- Do not write `state`, `attempt`, `claimed_at`, `claim_token`, `lease_expires_at`, `last_error`, `updated_at` or `sequence`. QueueBox owns them. A row created in a state other than `pending` can stall or deliver twice.
- Do not insert the outbox row outside the business transaction, on a second connection, or after the commit. Do not publish to a broker directly beside it.
- Do not write `NULL` into `headers`, or a JSON string of a JSON string into `payload`.
- Do not rely on order across keys, on order for rows with no key, or on commit order. Two writers of one key at the same time can commit out of order.
- Do not build a consumer on the inbox table of a push source. Consume at the destination.
- Do not put other work in the pull claim transaction on SQL Server, and do not start a handler before that claim commits. Treat Msg 51000 from the SQL Server claim as transient and back off before a retry.
- Do not set a SQL Server driver timeout below 30 seconds for the pull client.
- Do not scale a busy SQL Server pull source with more workers; the claim serializes per source. Split it into more sources.
- Do not do non-idempotent external work in a pull handler; the transaction cannot roll it back.
- Do not answer 2xx before the receiver has stored the message.
