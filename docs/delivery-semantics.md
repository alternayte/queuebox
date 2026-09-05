# Delivery semantics

This document states what QueueBox promises for an accepted message, and what the
application must do to hold its side of the contract.

## Push and pull

Every source declares how its messages leave the inbox.

```yaml
sources:
  orders:
    type: http
    path: /orders
    idempotencyKeyPath: $.id
    consumption: pull   # default: push
```

The value is stored on the row at receipt, so a later change of the configuration never
alters a message that the inbox already holds.

| | `push` | `pull` |
| --- | --- | --- |
| Who moves the message | The inbox relay | Your worker |
| Needs a topic and a route | Yes | No |
| The relay claims the row | Yes | Never |
| `state = 'processed'` means | QueueBox forwarded the row into the outbox | Your application finished the work |

The relay claims push rows only. A pull row is invisible to it, so a pull source needs no
topic, no route and no destination. Rows that existed before the upgrade are push rows,
which keeps the previous behaviour.

`processed` therefore carries two different meanings, and the two must not be confused.
For a push row it says the message reached the outbox; delivery to the destination is a
separate outbox state. For a pull row it says the application completed the business
work.

For the pull claim, renewal, completion, retry and dead-letter statements, see
[`examples/pull`](../examples/pull/README.md).

## The identity of a message

Three identifiers travel with a forwarded message. Each answers a different question.

| Identifier | Where | Stable across |
| --- | --- | --- |
| `(source, idempotency_key)` | The inbox unique constraint | Every retry and every replay |
| `x-inbox-id` | An outbox header | The inbox row |
| `X-Message-Id` | An outbox header and the outbox `id` | One outbox row, every delivery attempt |

The replay identity is `(source, idempotency_key)`, and it is source-qualified on
purpose. Two sources can send the same event ID and mean different events, so the source
is part of the identity.

A replay of an inbox row creates a **new** outbox row with a new `X-Message-Id`. A
receiver that deduplicates on `X-Message-Id` alone therefore accepts the replay as new
work. Deduplicate relay traffic on `x-idempotency-key`, which carries the inbox
idempotency key unchanged.

Join the two tables through the header when you investigate a message:

```sql postgres
SELECT o.id, o.topic, o.state, o.attempt, i.source, i.idempotency_key
FROM outbox o
JOIN inbox i ON i.id = (o.headers ->> 'x-inbox-id')::uuid
WHERE i.source = 'stripe' AND i.idempotency_key = 'evt_123';
```

```sql sqlserver
SELECT o.id, o.topic, o.state, o.attempt, i.source, i.idempotency_key
FROM outbox o
JOIN inbox i ON i.id = CAST(JSON_VALUE(o.headers, '$."x-inbox-id"') AS UNIQUEIDENTIFIER)
WHERE i.source = N'stripe' AND i.idempotency_key = N'evt_123';
```

## HTTP delivery

QueueBox sends the payload as the body of a POST, with the headers listed in
[the integration contract](integration.md). Every 2xx status completes the delivery, and
no other status does.

### 202 transfers durable responsibility

A `202 Accepted` completes the delivery exactly as a `200 OK` does. QueueBox marks the
outbox row `sent` and never sends that row again.

Answer 202 only after the message is durable at the receiver. If the receiver answers 202
and then loses the message in memory, the message is gone: QueueBox holds no copy that it
will retry, because a 2xx is a promise that the receiver took responsibility. A receiver
that cannot store the message yet must answer a non-2xx status, so that QueueBox retries.

### Duplicates

Delivery is at least once. A receiver must be idempotent. Two situations create a
duplicate even when nothing is broken.

- The response is lost. The receiver stored the message and answered, the answer never
  arrived, and QueueBox retries the same `X-Message-Id`.
- The claim expires during a slow publish. QueueBox publishes, another replica takes over
  the row and publishes it again. QueueBox reports this and names the setting that
  removes the cause: raise `outbox.claimTimeoutMs` above the slowest publish.

A duplicate is never silently dropped, and QueueBox never rolls a delivery back.

### An idempotent receiver

Store the identifier and the effect in one transaction. A repeat then finds the row and
changes nothing, whatever the reason for the repeat.

```sql postgres
BEGIN;

INSERT INTO delivery_receipts (message_id, idempotency_key, received_at)
VALUES ($1, $2, now())
ON CONFLICT (idempotency_key) DO NOTHING;

-- Zero rows means this is a repeat. Skip the business work and answer 200.
-- One row means this is new work. Apply the business change here, in this
-- transaction, and answer 200 only after the commit succeeds.

COMMIT;
```

```sql sqlserver
BEGIN TRANSACTION;

INSERT INTO delivery_receipts (message_id, idempotency_key, received_at)
SELECT @message_id, @idempotency_key, SYSUTCDATETIME()
WHERE NOT EXISTS (
    SELECT 1 FROM delivery_receipts WITH (UPDLOCK, HOLDLOCK)
    WHERE idempotency_key = @idempotency_key
);

COMMIT TRANSACTION;
```

Use `x-idempotency-key` as the key for relay traffic and `X-Message-Id` for traffic that
a producer wrote directly into the outbox. Answer a 2xx only after that transaction
committed. A 2xx before the commit turns a receiver crash into a lost message, because
QueueBox will not send the message again.

## Retention

Retention is off by default. Turn it on for each table separately, as described in the
[configuration reference](configuration.md).

Retention never deletes active work. It deletes outbox rows in state `sent` or `dead`,
and inbox rows in state `processed` or `dead`. A row in `pending` or `processing` stays,
whatever its age.

Deleting an inbox row ends deduplication for that message. The unique constraint on
`(source, idempotency_key)` is what rejects a repeat, and a deleted row no longer rejects
anything. Set the inbox retention age above the longest window in which a sender can
repeat a delivery, or the repeat is accepted as a new message.
