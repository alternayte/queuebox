# The message flow

This document explains how a message moves through QueueBox. `docs/architecture.md` holds the
diagrams. `docs/integration.md` holds the contract that an adopter writes against.

## Message Flow

### Inbox (Receiving Messages)

1. External system sends a webhook to the path that `inbox.basePath` and the `path` of the source
   build, for example `/inbox/stripe`. The NAME of the source does not enter the path.
2. QueueBox extracts the idempotency key with the configured JSONPath
3. Optional transform applied to the payload
4. Message stored in the `inbox` table with state `pending`. The unique index on
   `(source, idempotency_key)` detects a duplicate HERE, and the route answers 200 with
   `{"status":"duplicate"}`

   The order matters. The transform runs BEFORE the duplicate check, so a repeat of a stored
   message whose transform now fails answers 422, not 200. The transform is the gate on what
   enters the inbox, and a payload that cannot be transformed does not become a stored row.
5. Return 202 Accepted. The message is stored, not delivered. See F-078 and
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

This path applies to a source with `consumption: push`, which is the default. The relay
claims push rows only. A source with `consumption: pull` stops after the store step, and
the application claims the row itself. See
[the delivery semantics](delivery-semantics.md).

The full path of a push message is: receive, deduplicate, transform, store, forward, route,
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
| `headers` | `x-inbox-id`, `x-source`, `x-idempotency-key`, and `X-Correlation-Id` when the message carries one |

The topic template supports `{{ source }}` and `{{ eventType }}`. The default of an HTTP source is
`{{ eventType }}`. The default of an AMQP source is `{{ source }}`, because an AMQP message carries
no event type of its own. The relay marks the message `dead` when the template renders empty.

An AMQP source takes the event type from `eventTypePath` in the body, and then from the AMQP header
`x-event-type`. To use `{{ eventType }}` in the template of an AMQP source, set `eventTypePath`, or
set `eventTypeFromHeader: true` to declare that every publisher sets the header. QueueBox refuses
the start when the template uses `{{ eventType }}` and neither field is set.

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

### A rejected message on an AMQP source

The transform can reject a message. The two source types answer differently.

- **HTTP source.** QueueBox answers 422. The caller still holds the message, and the caller can
  correct it and send it again. QueueBox stores no row.
- **AMQP source.** No caller holds the message. QueueBox therefore stores the inbox row with the
  original payload in state `dead`, and only then acknowledges the delivery. QueueBox declares no
  dead-letter exchange, so the row is the only copy.

The row is written in state `dead` in ONE transaction. A store in state `pending` followed by a
separate mark dead commits a claimable row first. The relay polls in its own coroutine, so it can
claim that row and forward a payload that the transform rejected. The single transaction removes
that window. The row never exists in state `pending`.

The order is mandatory. If the store fails, QueueBox does not acknowledge the delivery. It nacks
with requeue, and the broker keeps the message.

A repeat of the same idempotency key hits the unique index. The earlier row already holds a
payload, so QueueBox stores nothing more. QueueBox acknowledges the delivery, and it marks
nothing.

**QueueBox marks no row on the duplicate path.** The natural key is not unique to one delivery.
Two events can carry the same value in the field that `idempotencyKeyPath` names. A mark by the
natural key therefore destroys an earlier, healthy row. A `pending` row is then never forwarded,
which breaks the at-least-once guarantee below. A `processed` row goes back to `dead`, and an
operator who returns that row to `pending` gets a second delivery. The mark is also unnecessary. A
rejected message reaches the single-transaction store only, and that store writes state `dead`. No
path leaves a rejected message in state `pending`, so no row needs repair. See the fourth review
gate, defect 1.

A message with no idempotency key gets a stable SHA-256 digest of the body as its key. A
redelivery of the identical message therefore hits the unique index, and the inbox holds one row.

**Configure a key source when two events can carry the same body.** The digest is the last resort.
QueueBox uses it only when the `x-idempotency-key` header, the configured `idempotencyKeyPath` and
the AMQP `messageId` property all give nothing. In that case the publisher has given QueueBox
nothing that separates one message from another, so QueueBox treats identical bytes as one
message. Two distinct events with an identical body then deduplicate to one row, and the second
event is NOT forwarded. Set `idempotencyKeyPath`, or publish the `x-idempotency-key` header, or
set the `messageId` property.

The relay never forwards a `dead` row. An operator can read the row, correct the transform, and
replay the payload.

**Guarantee.** Forwarding is at least once. If the transaction fails, the inbox row stays in
state `processing`, and the reclaim step returns it to `pending` after `claimTimeoutMs`.

**The claim fence.** The reclaim step runs on a timer. It does not prove that the old owner
died, so a slow replica can outlive its own claim while another replica claims the same row.
Every terminal write is therefore fenced. The update matches the row, the state `processing`
and the claim token of the caller. The write reports whether it landed. A relay that lost the
claim rolls the outbox insert back in the same transaction, so the row reaches the outbox once.
The relay logs the loss and increments `queuebox_claims_lost_total{component="inbox"}`. Raise
`claimTimeoutMs` when that counter moves.

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
- The relay forwards one claimed batch sequentially, one message at a time. The outbox poller
  publishes with the configured concurrency, so parallelism happens at delivery, not at forward.

**Guarantee.** At most one message per aggregate identifier is in state `processing` at any
time, across every replica. The relay forwards the messages of one aggregate in creation order.

The advisory lock closes the window between two concurrent claims. The reclaim step opens a
second window, because it returns a row to `pending` on a timer. The claim fence closes that
window: the old owner cannot complete the row and cannot forward a second copy of it. Two
replicas therefore never write one aggregate into the outbox twice.

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
state           VARCHAR(50)         -- see architecture.md for the state set
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
state           VARCHAR(50)         -- see architecture.md for the state set
created_at      TIMESTAMP
processed_at    TIMESTAMP
claimed_at      TIMESTAMP           -- When the relay claimed the row (V3)
correlation_id  VARCHAR(128)        -- Identifier that follows the message (V5)
```

