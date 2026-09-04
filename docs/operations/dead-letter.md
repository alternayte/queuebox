# Dead letters: inspection and replay

A message reaches the state `dead` after `outbox.maxAttempts` failed delivery attempts. QueueBox
never deletes a dead message on its own. The retention job removes it after `retention.outbox.maxAge`.

This document gives the supported SQL to list a dead message and to requeue one.

## How to read the SQL in this document

Every fenced `sql` block in this document is executed by
`postgres/src/test/kotlin/org/nxtspec/RunbookSqlTest.kt` against the shipped schema. The
end to end test `app/src/test/kotlin/e2e/E2EDeadLetterReplayTest.kt` runs the requeue block and
asserts the message is then delivered.

Rules for the SQL blocks:

- Each block holds one or more statements. Each statement ends with a semicolon at the end of a
  line.
- A parameter appears as a placeholder. A placeholder starts with a colon, for example
  `:message_id`. Replace it with a real value before you run the statement.
- The supported placeholders are `:message_id`, `:topic`, `:state`, `:destination` and `:limit`.
- An `<!-- sql-id: name -->` comment before a block gives that block a name. A test selects the
  block by that name.

## List the dead messages

Count the dead messages per topic:

<!-- sql-id: list-dead-by-topic -->
```sql
SELECT topic, count(*) AS dead_count
FROM outbox
WHERE state = 'dead'
GROUP BY topic
ORDER BY dead_count DESC;
```

List the newest dead messages with the failure reason:

<!-- sql-id: list-dead -->
```sql
SELECT id, topic, key, attempt, max_attempts, created_at, updated_at, last_error
FROM outbox
WHERE state = 'dead'
ORDER BY updated_at DESC
LIMIT :limit;
```

Read one dead message in full, with its payload and its headers:

<!-- sql-id: show-dead -->
```sql
SELECT id, topic, key, payload, headers, attempt, max_attempts, scheduled_at, created_at,
       updated_at, claimed_at, last_error
FROM outbox
WHERE id = :message_id
  AND state = 'dead';
```

## Requeue one dead message

Correct the cause of the failure first. A requeue against a destination that is still broken
produces a second dead message.

The requeue sets the state to `pending`, resets `attempt` to zero and clears `scheduled_at` to
the current time. The poller then claims the row on its next cycle.

<!-- sql-id: requeue-one -->
```sql
UPDATE outbox
SET state = 'pending',
    attempt = 0,
    scheduled_at = CURRENT_TIMESTAMP,
    claimed_at = NULL,
    last_error = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :message_id
  AND state = 'dead';
```

The clause `AND state = 'dead'` protects a message that another operator already requeued. The
statement reports zero updated rows in that case.

Confirm the new state:

<!-- sql-id: verify-requeue -->
```sql
SELECT id, state, attempt, scheduled_at, last_error
FROM outbox
WHERE id = :message_id;
```

The message is delivered when the state becomes `sent`.

## Requeue every dead message of one topic

Use this form after you repair a destination. Requeue one message first and confirm the
delivery.

<!-- sql-id: requeue-topic -->
```sql
UPDATE outbox
SET state = 'pending',
    attempt = 0,
    scheduled_at = CURRENT_TIMESTAMP,
    claimed_at = NULL,
    last_error = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE state = 'dead'
  AND topic = :topic;
```

## Requeue a dead inbox message

The inbox relay dead-letters a row that it cannot forward. The requeue sets the state back to
`pending` and clears `claimed_at`.

<!-- sql-id: requeue-inbox -->
```sql
UPDATE inbox
SET state = 'pending',
    claimed_at = NULL,
    processed_at = NULL
WHERE id = :message_id
  AND state = 'dead';
```

## Discard a dead message

Delete a message only when you accept the loss of the event. Copy the payload first.

<!-- sql-id: discard-dead -->
```sql
DELETE FROM outbox
WHERE id = :message_id
  AND state = 'dead';
```
