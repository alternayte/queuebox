# @queuebox/inbox

A pull-inbox worker for [QueueBox](https://github.com/alternayte/queuebox).

QueueBox makes the push path need no code. The pull path needed five SQL statements, a renewal
timer and a lease discipline, written by hand in every application. This package holds all of it.

## What it guarantees

**Your writes and the completion commit together.** That is the whole point of the pull path.
The handler receives the transaction, and the library runs the completion inside it. If the
completion affects no row, the lease was lost, another worker owns the message, and the library
rolls your writes back and reports nothing as done.

## Requirements

| | |
|---|---|
| Runtime | Node 22 or later, Bun, or Deno |
| Database | PostgreSQL through `pg`, or SQL Server through `mssql` |
| QueueBox | 0.1.0 or later, which is the V6 schema |

The package depends on no driver. It states the small interface it needs and ships an adapter
for each, so one package serves both dialects and your application brings the driver it has.

## Install

```
npm install @queuebox/inbox
```

## PostgreSQL

```ts
import pg from "pg";
import { InboxWorker, fromPg } from "@queuebox/inbox";

const pool = new pg.Pool({ connectionString: process.env.QUEUEBOX_DB });

const worker = new InboxWorker(fromPg(pool), { source: "orders", batchSize: 10, leaseMs: 30_000 });

await worker.run(async (message, tx) => {
  const payload = message.payload as { id: string; total: number };

  await tx.query("INSERT INTO orders (id, total) VALUES ($1, $2)", [payload.id, payload.total]);
});
```

## SQL Server

```ts
import mssql from "mssql";
import { InboxWorker, fromMssql } from "@queuebox/inbox";

const pool = await new mssql.ConnectionPool(process.env.QUEUEBOX_DB!).connect();

const worker = new InboxWorker(fromMssql(mssql, pool), { source: "orders", dialect: "sqlserver" });

await worker.run(async (message, tx) => {
  const payload = message.payload as { id: string; total: number };

  await tx.query("INSERT INTO orders (id, total) VALUES (@p1, @p2)", [payload.id, payload.total]);
});
```

The `mssql` module itself is a parameter, because the adapter needs its `Transaction` and
`Request` constructors and this package imports no driver.

## Placeholders

Both dialects bind **positionally**, because `pg` accepts no named parameter. Write the
placeholder style of your own database: `$1` for PostgreSQL, `@p1` for SQL Server. The values
are the second argument, in the order the statement names them.

## What the handler receives

| Field | Meaning |
|-------|---------|
| `message.id` | The inbox row identifier |
| `message.source` | The source name |
| `message.idempotencyKey` | The deduplication key. The full identity is the source and this key together |
| `message.aggregateId` | Nullable |
| `message.eventType` | Nullable |
| `message.payload` | The JSON body, parsed |
| `message.attempt` | Zero on the first delivery |
| `message.correlationId` | Nullable, for logs |

The claim token is absent on purpose. The library owns the token, because a handler that could
reach it could complete a message out of band.

## Rules for a handler

1. Write every application change through the transaction the handler receives.
2. Do not commit and do not roll back. The library owns both.
3. Throw to fail the message. The library rolls the transaction back, so no partial write stays.
4. Honour the abort signal, which is the third argument. It aborts when the lease is lost and
   when a shutdown runs out of grace.
5. Do external work, such as an HTTP call, only where a repeat is safe. A transaction cannot roll
   back a call to another system. Deduplicate on the source and the idempotency key.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `source` | none, it is mandatory | The source whose messages this worker takes |
| `batchSize` | 10 | The largest number of messages one claim takes |
| `leaseMs` | 30000 | The lease duration. The renewal runs every third of it |
| `maxConcurrency` | the batch size | The largest number of handlers that run at one time |
| `pollIntervalMs` | 1000 | The wait after a claim that returned nothing |
| `shutdownGraceMs` | 30000 | How long a stop waits for the handlers already running |
| `dialect` | `"postgresql"` | `"postgresql"` or `"sqlserver"` |
| `schema` | the QueueBox names | The table and column names, when an operator mapped them |
| `retryPolicy` | `defaultRetryPolicy()` | What happens to a message whose handler threw |
| `logger` | none | The caller's logger. The library prints nothing without one |

## Failure, retry and the dead letter

The default policy retries while `attempt < maxAttempts`, with an exponential backoff and
jitter, and dead-letters after that. Only your application knows that a validation error must
never be retried while a timeout must, so write your own:

```ts
import { deadLetter, retryAfter } from "@queuebox/inbox";
import type { InboxRetryPolicy } from "@queuebox/inbox";

const policy: InboxRetryPolicy = (message, failure) => {
  // A bad payload never becomes good. Do not spend five attempts on it.
  if (failure instanceof SyntaxError) {
    return deadLetter();
  }

  return message.attempt >= 5 ? deadLetter() : retryAfter(2 ** message.attempt * 1000);
};
```

## Shutdown

Abort the signal you passed to `run`. The worker stops claiming at once. The handlers that
already run keep `shutdownGraceMs`, and a handler that does not finish inside it is aborted. Its
message is abandoned: the library completes nothing, spends no attempt on it, and lets the lease
expire so another worker takes it.

## Logging

Pass a `logger`. Without one the library prints nothing. Every message and every error passes
through the same redaction QueueBox itself uses, so a connection string password reaches neither
a log line nor the `last_error` column.

## Mapped table and column names

QueueBox lets an operator rename the inbox table and its columns. Name them here as well:

```ts
import { defaultSchema } from "@queuebox/inbox";

const worker = new InboxWorker(fromPg(pool), {
  source: "orders",
  schema: { ...defaultSchema, table: "qb_messages", state: "row_state" },
});
```

Every name is quoted and checked before it reaches the database, so a mapping cannot carry SQL.

## Runtimes

The package ships ESM and CJS, and it uses no Node-only API in the hot path, so it runs on Node,
Bun and Deno. The constraint is the database driver, not the runtime. The unit tests run under
Node and under Bun in continuous integration.

## Releases

The library releases on its own tag, so a library fix never needs a QueueBox release.

```bash
git tag typescript-v0.1.1 && git push origin typescript-v0.1.1
```

## License

Apache-2.0.
