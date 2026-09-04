# The message flow

This document explains how a message moves through QueueBox. `docs/architecture.md` holds the
diagrams. `docs/integration.md` holds the contract that an adopter writes against.

## Message Flow

### Inbox (Receiving Messages)

1. External system sends webhook to `/inbox/{source}`
2. QueueBox extracts idempotency key using configured JSONPath
3. Duplicate check. If the key exists for this source, return 200 with `{"status":"duplicate"}`
4. Optional transform applied to payload
5. Message stored in `inbox` table with state `pending`
6. Return 202 Accepted. The message is stored, not delivered. See F-078 and
   [adr/0002-inbox-accept-returns-202.md](adr/0002-inbox-accept-returns-202.md)
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

## Database Schema

QueueBox creates two tables:

**outbox:**
```
id              UUID PRIMARY KEY
topic           VARCHAR(255)
key             VARCHAR(255)        -- Optional partition/ordering key
payload         JSONB
headers         JSONB
state           VARCHAR(50)         -- 'pending', 'processing', 'sent', 'failed', 'dead'
attempt         INTEGER
max_attempts    INTEGER
scheduled_at    TIMESTAMP
created_at      TIMESTAMP
updated_at      TIMESTAMP
claimed_at      TIMESTAMP           -- When the poller claimed the row (V3)
last_error      TEXT                -- Why the last attempt failed. Redacted and truncated (V4)
```

**inbox:**
```
id              UUID PRIMARY KEY
source          VARCHAR(255)
idempotency_key VARCHAR(255)        -- Unique per source
aggregate_id    VARCHAR(255)
event_type      VARCHAR(255)
payload         JSONB
state           VARCHAR(50)         -- 'pending', 'processing', 'processed', 'dead'
created_at      TIMESTAMP
processed_at    TIMESTAMP
claimed_at      TIMESTAMP           -- When the relay claimed the row (V3)
correlation_id  VARCHAR(128)        -- Identifier that follows the message (V5)
```

