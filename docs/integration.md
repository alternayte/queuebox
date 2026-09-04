# Integration contract

This document tells an application how to hand a message to QueueBox, and how to receive a message
that QueueBox forwards. It is the product surface.

An application sends a message with one `INSERT` into the `outbox` table. The insert runs inside the
transaction of the business write. QueueBox reads the table, routes each row to a destination, and
delivers it.

Every SQL statement in this document is executed by
`app/src/test/kotlin/docs/IntegrationDocSqlTest.kt` against the shipped migration set. The
PostgreSQL statements run against a PostgreSQL container. The SQL Server statements run against a
SQL Server container. Each inserted outbox row is delivered end to end by the running poller.

---

## 1. The outbox columns

The shipped schema is `postgres/src/main/resources/db/postgresql/` and
`sqlserver/src/main/resources/db/sqlserver/`. The table below states the shipped definition.

| Column | PostgreSQL type | SQL Server type | Null | Default | Who writes it |
|--------|-----------------|-----------------|------|---------|---------------|
| `id` | `UUID` | `UNIQUEIDENTIFIER` | no | `gen_random_uuid()` / `NEWID()` | the application, or the default |
| `topic` | `VARCHAR(255)` | `NVARCHAR(255)` | no | none | the application. Required. |
| `key` | `VARCHAR(255)` | `NVARCHAR(255)` | yes | none | the application. Optional. |
| `payload` | `JSONB` | `NVARCHAR(MAX)` | no | none | the application. Required. |
| `headers` | `JSONB` | `NVARCHAR(MAX)` | no | `'{}'` | the application. Optional. |
| `state` | `VARCHAR(50)` | `NVARCHAR(50)` | no | `'pending'` | QueueBox |
| `attempt` | `INTEGER` | `INT` | no | `0` | QueueBox |
| `max_attempts` | `INTEGER` | `INT` | no | `5` | the application. QueueBox writes `outbox.maxAttempts` into every row it creates. |
| `scheduled_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | no | `CURRENT_TIMESTAMP` / `GETUTCDATE()` | the application, or the default |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | no | `CURRENT_TIMESTAMP` / `GETUTCDATE()` | the default |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | no | `CURRENT_TIMESTAMP` / `GETUTCDATE()` | QueueBox |
| `claimed_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | yes | none | QueueBox |
| `last_error` | `TEXT` | `NVARCHAR(MAX)` | yes | none | QueueBox |

**Only two columns are required: `topic` and `payload`.** Every other column has a default, or
accepts null.

The `attempt` column counts the failed deliveries. It is `0` on the first delivery, and the retry
raises it. The poller compares it against the `max_attempts` column of the SAME row, so a row
value overrides the configured `outbox.maxAttempts`. Set `max_attempts` on the insert to give one
message a different ceiling. Omit it, and the row takes the schema default of `5`.
[configuration.md](configuration.md) states the precedence in full.

### Can `headers` be null?

No. The column is `NOT NULL` with the default `'{}'`. An application must omit the column, or write
a JSON object. An explicit `NULL` breaks the insert. To send no header, omit the column.

### Columns the application must not write

QueueBox owns `state`, `attempt`, `claimed_at` and `last_error`. Write `state` only with the value
`pending`, which is the default. A row that an application creates in another state can stall or can
deliver twice.

### What `topic` must look like

QueueBox matches `topic` against the `topicPattern` of each route in configuration order. The first
route that matches wins. `compileTopicPattern` in
`outbox-service/src/main/kotlin/MessageRouter.kt` compiles the pattern:

- The match is anchored. The pattern must match the whole topic.
- `*` matches one segment. A segment holds no dot.
- `**` matches any text, dots included.
- Every other character is a literal. A dot, a dash and a plus carry no special meaning.

| Pattern | Matches | Does not match |
|---------|---------|----------------|
| `order.*` | `order.created`, `order.paid` | `order`, `order.item.added` |
| `order.**` | `order.created`, `order.item.added` | `order` |
| `**` | every topic | nothing |
| `order.created` | `order.created` | `order.updated` |

A topic that matches no route is marked `dead`. Choose a dotted, lower case topic, for example
`order.created`. The topic column holds 255 characters at most.

---

## 2. The worked insert

The examples use one business table. The example creates it, because the test executes every
statement. Your application has its own table already.

```sql postgres
CREATE TABLE IF NOT EXISTS orders (
    id          UUID PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### PostgreSQL

The business write and the outbox insert run in one transaction. `payload` and `headers` are
`JSONB`, so a string literal is cast.

```sql postgres
BEGIN;

INSERT INTO orders (id, customer_id, amount)
VALUES ('11111111-1111-1111-1111-111111111111', 'cust-42', 99.99);

INSERT INTO outbox (topic, key, payload, headers)
VALUES (
    'order.created',
    'cust-42',
    '{"orderId":"11111111-1111-1111-1111-111111111111","amount":99.99}'::jsonb,
    '{"X-Tenant":"acme"}'::jsonb
);

COMMIT;
```

The shortest legal insert names only the two required columns.

```sql postgres
BEGIN;

INSERT INTO orders (id, customer_id, amount)
VALUES ('22222222-2222-2222-2222-222222222222', 'cust-7', 12.00);

INSERT INTO outbox (topic, payload)
VALUES ('order.paid', '{"orderId":"22222222-2222-2222-2222-222222222222"}'::jsonb);

COMMIT;
```

To delay a message, set `scheduled_at`. QueueBox claims a row only when `scheduled_at` has passed.

```sql postgres
BEGIN;

INSERT INTO outbox (topic, payload, scheduled_at, max_attempts)
VALUES (
    'order.reminder',
    '{"orderId":"22222222-2222-2222-2222-222222222222"}'::jsonb,
    CURRENT_TIMESTAMP,
    10
);

COMMIT;
```

### SQL Server

`payload` and `headers` are `NVARCHAR(MAX)`. `key` is a reserved word, so it carries brackets. The
example creates the business table again, because the test executes every statement.

```sql sqlserver
IF OBJECT_ID('orders', 'U') IS NULL
CREATE TABLE orders (
    id          UNIQUEIDENTIFIER PRIMARY KEY,
    customer_id NVARCHAR(64) NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    created_at  DATETIME2 NOT NULL DEFAULT GETUTCDATE()
);
```

```sql sqlserver
BEGIN TRANSACTION;

INSERT INTO orders (id, customer_id, amount)
VALUES ('11111111-1111-1111-1111-111111111111', N'cust-42', 99.99);

INSERT INTO outbox (topic, [key], payload, headers)
VALUES (
    N'order.created',
    N'cust-42',
    N'{"orderId":"11111111-1111-1111-1111-111111111111","amount":99.99}',
    N'{"X-Tenant":"acme"}'
);

COMMIT TRANSACTION;
```

The shortest legal insert names only the two required columns.

```sql sqlserver
BEGIN TRANSACTION;

INSERT INTO outbox (topic, payload)
VALUES (N'order.paid', N'{"orderId":"22222222-2222-2222-2222-222222222222"}');

COMMIT TRANSACTION;
```

---

## 3. ORM examples

**QueueBox documents raw SQL only.** The repository ships no ORM example, and QueueBox publishes no
client library for an application. Any ORM can write the row, because the row is one insert into one
table. Use the raw SQL above as the contract, and make sure of two things:

1. The insert joins the transaction of the business write. Section 4 states the rule.
2. `payload` and `headers` hold a JSON object. An ORM that stores a JSON string of a string breaks
   a transform and a routing key template.

---

## 4. The insert must share the transaction of the business write

**Rule: open one transaction, write the business rows, insert the outbox row, then commit.**

This shared transaction is the whole point of the outbox pattern. The database gives the message and
the business state one atomic commit, so the two can never disagree.

- If the transaction commits, both the order row and the outbox row exist. QueueBox then delivers
  the message. Delivery is at least once.
- If the transaction rolls back, neither row exists. No message announces an order that does not
  exist.

An application that publishes to a broker directly, outside the transaction, has no such guarantee.
The commit can fail after the publish, which announces an order that no longer exists. The publish
can fail after the commit, which loses the message. There is no ordering of the two writes that
removes the failure, because they are two systems.

Two more rules follow:

- Do not open a second connection for the outbox insert. A second connection is a second
  transaction, and the guarantee is gone.
- Keep the transaction short. QueueBox sees the row only after the commit.

---

## 5. The reading side: the inbox

An application receives a message through a source. A source is an HTTP endpoint or a RabbitMQ
queue that QueueBox owns. QueueBox stores each accepted message in the `inbox` table, and
deduplicates on `(source, idempotency_key)`.

| Column | PostgreSQL type | SQL Server type | Null | Default |
|--------|-----------------|-----------------|------|---------|
| `id` | `UUID` | `UNIQUEIDENTIFIER` | no | `gen_random_uuid()` / `NEWID()` |
| `source` | `VARCHAR(255)` | `NVARCHAR(255)` | no | none |
| `idempotency_key` | `VARCHAR(255)` | `NVARCHAR(255)` | no | none |
| `aggregate_id` | `VARCHAR(255)` | `NVARCHAR(255)` | yes | none |
| `event_type` | `VARCHAR(255)` | `NVARCHAR(255)` | yes | none |
| `payload` | `JSONB` | `NVARCHAR(MAX)` | no | none |
| `state` | `VARCHAR(50)` | `NVARCHAR(50)` | no | `'pending'` |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | no | `CURRENT_TIMESTAMP` / `GETUTCDATE()` |
| `processed_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | yes | none |
| `claimed_at` | `TIMESTAMP WITH TIME ZONE` | `DATETIME2` | yes | none |
| `correlation_id` | `VARCHAR(128)` | `NVARCHAR(128)` | yes | none |

The pair `(source, idempotency_key)` carries a unique constraint, which is the deduplication.

### QueueBox forwards the inbox rows itself

**An application does not poll the inbox table.** Decision 1 of section 2A of `hardening-doc.md` is
closed: QueueBox forwards an inbox row itself. `InboxRelay` claims each stored row, writes it into
the `outbox` table with the topic of the source, and marks the inbox row processed. The outbox
machinery then routes, transforms and delivers it.

An application therefore consumes the forwarded message **at the destination**, not from the inbox
table. The destination is the HTTP endpoint or the RabbitMQ exchange that the matching route names.
Point a route at your own service, and receive the message there.

The inbox table is the durability record and the deduplication record of QueueBox. Read it for an
operational question, for example "did the webhook arrive". Do not build a consumer on it. The
retention job deletes a processed row after the retention period.

```sql postgres
SELECT id, source, idempotency_key, event_type, state, created_at, processed_at
FROM inbox
WHERE source = 'stripe'
ORDER BY created_at DESC
LIMIT 20;
```

```sql sqlserver
SELECT TOP 20 id, source, idempotency_key, event_type, state, created_at, processed_at
FROM inbox
WHERE source = N'stripe'
ORDER BY created_at DESC;
```

### What arrives at the destination

An HTTP destination receives the payload as the body, and these headers:

| Header | Meaning |
|--------|---------|
| `X-Message-Id` | The `id` of the outbox row. Use it to deduplicate. |
| `X-Topic` | The `topic` of the outbox row. |
| `X-Attempt` | The delivery attempt, counted from `0`. |

Delivery is at least once, so a destination must be idempotent. Deduplicate on `X-Message-Id`.
