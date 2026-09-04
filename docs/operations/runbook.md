# QueueBox operations runbook

This runbook covers five operational scenarios. Each scenario gives concrete SQL or a concrete
command. The SQL matches the shipped PostgreSQL schema in
`postgres/src/main/resources/db/postgresql/`.

## How to read the SQL in this document

Every fenced `sql` block in this document is executed by the test
`postgres/src/test/kotlin/org/nxtspec/RunbookSqlTest.kt`. The test extracts each block and runs
each statement against the shipped schema. A statement that is wrong fails the test.

Rules for the SQL blocks:

- Each block holds one or more statements. Each statement ends with a semicolon at the end of a
  line.
- A parameter appears as a placeholder. A placeholder starts with a colon, for example
  `:message_id`. The test substitutes a test value for each placeholder.
- The supported placeholders are `:message_id`, `:topic`, `:state`, `:destination` and `:limit`.
- A fenced block in another language, for example `bash` or `yaml`, is a command. The test does
  not execute it.
- An `<!-- sql-id: name -->` comment before a block gives that block a name. Another test can
  select the block by that name.

Replace each placeholder with a real value before you run a statement.

---

## Scenario 1: Inspect dead-lettered messages

A message reaches the state `dead` after `outbox.maxAttempts` failed delivery attempts. The
column `last_error` holds the redacted reason for the last failure.

Count the dead messages per topic:

```sql
SELECT topic, count(*) AS dead_count
FROM outbox
WHERE state = 'dead'
GROUP BY topic
ORDER BY dead_count DESC;
```

List the most recent dead messages with the reason:

```sql
SELECT id, topic, key, attempt, max_attempts, updated_at, last_error
FROM outbox
WHERE state = 'dead'
ORDER BY updated_at DESC
LIMIT :limit;
```

Read one message in full:

```sql
SELECT id, topic, key, payload, headers, attempt, scheduled_at, created_at, updated_at,
       claimed_at, last_error
FROM outbox
WHERE id = :message_id;
```

Group the dead messages by the first part of the failure reason:

```sql
SELECT left(last_error, 60) AS reason, count(*) AS dead_count
FROM outbox
WHERE state = 'dead'
GROUP BY left(last_error, 60)
ORDER BY dead_count DESC;
```

The inbox has a `dead` state too. List the dead inbox rows:

```sql
SELECT id, source, idempotency_key, event_type, created_at
FROM inbox
WHERE state = 'dead'
ORDER BY created_at DESC
LIMIT :limit;
```

Scrape the metric `queuebox_outbox_messages_total{status="dead"}` to see the dead count over
time:

```bash
curl -s http://localhost:8080/metrics | grep queuebox_outbox_messages_total
```

---

## Scenario 2: Replay a dead-lettered message

`docs/operations/dead-letter.md` holds the full replay procedure. This section gives the short
form.

Correct the cause of the failure first. A replay against a destination that is still broken
produces a second dead message.

Replay one message:

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

Replay every dead message of one topic:

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

Confirm the result:

```sql
SELECT id, state, attempt, scheduled_at
FROM outbox
WHERE id = :message_id;
```

---

## Scenario 3: The pending gauge grows

The gauge `queuebox_outbox_messages_pending` reports the number of rows in the state `pending`.
A gauge that grows means the poller delivers slower than the producer writes.

Read the gauge:

```bash
curl -s http://localhost:8080/metrics | grep queuebox_outbox_messages_pending
```

Measure the backlog and its age:

```sql
SELECT count(*) AS pending_count,
       min(created_at) AS oldest_created_at,
       max(created_at) AS newest_created_at
FROM outbox
WHERE state = 'pending';
```

Find the age of the oldest row that is due now:

```sql
SELECT id, topic, created_at, scheduled_at, attempt
FROM outbox
WHERE state = 'pending'
  AND scheduled_at <= CURRENT_TIMESTAMP
ORDER BY scheduled_at ASC
LIMIT :limit;
```

Separate a real backlog from a retry backlog. A large `attempt` value means the destination
rejects the messages:

```sql
SELECT attempt, count(*) AS pending_count
FROM outbox
WHERE state = 'pending'
GROUP BY attempt
ORDER BY attempt ASC;
```

Check for rows that stay in the state `processing`. The reclaim step returns such a row to
`pending` after `outbox.claimTimeoutMs`:

```sql
SELECT count(*) AS stuck_count, min(claimed_at) AS oldest_claim
FROM outbox
WHERE state = 'processing';
```

Actions, in order:

1. Confirm the destination is healthy. Use scenario 5.
2. Raise `outbox.concurrency` if the destination accepts more parallel requests.
3. Raise `outbox.batchSize` if each poll cycle returns a full batch.
4. Lower `outbox.pollIntervalMs` if the batch is not full and the backlog still grows.
5. Raise `database.poolSize` if the pool limits the poller. Use scenario 4.

---

## Scenario 4: Size the pool and the batch

Four settings control the throughput of the outbox poller:

| Setting | Default | Effect |
|---------|---------|--------|
| `database.poolSize` | 10 | The maximum number of open database connections. |
| `outbox.batchSize` | 100 | The number of messages that one poll cycle claims. |
| `outbox.concurrency` | 8 | The number of messages that QueueBox publishes at the same time. |
| `outbox.pollIntervalMs` | 100 | The wait between two poll cycles. |

Rules:

- Keep `database.poolSize` larger than `outbox.concurrency`. The poller, the relay, the
  retention job and the HTTP API all take a connection.
- Keep `outbox.batchSize` larger than `outbox.concurrency`. A batch smaller than the
  concurrency leaves publisher slots idle.
- A large `outbox.batchSize` holds the claimed rows in the state `processing` for longer. Keep
  `outbox.claimTimeoutMs` larger than the time to publish one full batch.
- The destination is the usual limit, not the database. Raise the concurrency first.

Set the values in the YAML file:

```yaml
database:
  poolSize: 20
outbox:
  pollIntervalMs: 100
  batchSize: 200
  concurrency: 16
  claimTimeoutMs: 300000
```

Or set them through the environment. One underscore separates one level of the path from the next
level, and a leaf name of more than one word carries no underscore:

```bash
export QUEUEBOX_DATABASE_POOLSIZE=20
export QUEUEBOX_OUTBOX_BATCHSIZE=200
export QUEUEBOX_OUTBOX_CONCURRENCY=16
export QUEUEBOX_OUTBOX_POLLINTERVALMS=100
```

Check the database side of the pool. Compare the open connections with `poolSize` times the
number of instances:

```sql
SELECT count(*) AS open_connections
FROM pg_stat_activity
WHERE datname = current_database();
```

Check the server limit:

```sql
SHOW max_connections;
```

A pool that is too small shows as a slow claim. Read the publish histogram and the pending
gauge together:

```bash
curl -s http://localhost:8080/metrics | grep queuebox_outbox_processing_duration_seconds
```

---

## Scenario 5: A destination is slow

A slow destination raises `queuebox_outbox_publish_duration_seconds` and, after that, the
pending gauge.

Read the publish duration per destination type:

```bash
curl -s http://localhost:8080/metrics | grep queuebox_outbox_publish_duration_seconds
```

Read the readiness endpoint. It reports the named contributors:

```bash
curl -s http://localhost:8080/health/ready
```

Find the topics that retry. A retry is the first signal of a slow or failing destination:

```sql
SELECT topic, count(*) AS retry_count, max(attempt) AS worst_attempt
FROM outbox
WHERE state = 'pending'
  AND attempt > 0
GROUP BY topic
ORDER BY retry_count DESC;
```

Read the last error text for the affected topic:

```sql
SELECT id, attempt, updated_at, last_error
FROM outbox
WHERE topic = :topic
  AND last_error IS NOT NULL
ORDER BY updated_at DESC
LIMIT :limit;
```

Measure the time between the creation and the last update of the delivered messages. A large
value means a slow destination:

```sql
SELECT topic,
       count(*) AS sent_count,
       avg(extract(epoch FROM (updated_at - created_at))) AS avg_seconds
FROM outbox
WHERE state = 'sent'
GROUP BY topic
ORDER BY avg_seconds DESC;
```

Actions, in order:

1. Test the destination directly. Compare the latency with the configured `timeoutMs`.
2. Raise the destination `timeoutMs` if the destination is slow but correct.
3. Lower `outbox.concurrency` if the destination rejects requests under load.
4. Raise `outbox.retryBaseDelayMs` to give the destination more time between attempts.
5. Inspect the dead messages with scenario 1 after the destination recovers.
