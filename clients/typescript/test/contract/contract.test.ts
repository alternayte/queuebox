import { after, before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";

import { InboxWorker, defaultRetryPolicy, defaultSchema, inboxSql } from "../../src/index.ts";
import type { InboxMessage, InboxTransaction } from "../../src/index.ts";
import type { DatabaseHarness } from "./harness.ts";
import { PostgresHarness } from "./postgres-harness.ts";
import { SqlServerHarness } from "./sqlserver-harness.ts";

const SOURCE = "orders";

/**
 * The contract of `clients/contract-tests.md`. Every harness runs the whole list, because a
 * difference between two dialects must be a difference of idiom, never of guarantee.
 */
function contract(harness: DatabaseHarness): void {
  describe(`the contract against ${harness.dialect}`, () => {
    before(async () => {
      await harness.start();
    });

    after(async () => {
      await harness.stop();
    });

    beforeEach(async () => {
      await harness.reset();
    });

    function worker(overrides: Record<string, unknown> = {}): InboxWorker {
      return new InboxWorker(harness.connections, {
        source: SOURCE,
        dialect: harness.dialect,
        pollIntervalMs: 50,
        shutdownGraceMs: 10_000,
        ...overrides,
      });
    }

    async function writeApplicationRow(transaction: InboxTransaction, key: string): Promise<void> {
      await transaction.query(harness.insertApplicationRow, [key]);
    }

    /** Run the worker until it handled the expected number of messages, then stop it. */
    async function runUntil(
      expected: number,
      handler: (message: InboxMessage, transaction: InboxTransaction, signal: AbortSignal) => Promise<void>,
      overrides: Record<string, unknown> = {},
    ): Promise<void> {
      const stop = new AbortController();
      let handled = 0;
      let settle = (): void => undefined;
      const reached = new Promise<void>((resolve) => {
        settle = resolve;
      });

      const run = worker(overrides).run(async (message, transaction, signal) => {
        try {
          await handler(message, transaction, signal);
        } finally {
          handled += 1;

          if (handled >= expected) {
            settle();
          }
        }
      }, stop.signal);

      await reached;
      // The failure path runs after the handler returned, so give it room to finish.
      await new Promise((resolve) => setTimeout(resolve, 500));
      stop.abort();
      await run;
    }

    // Item 1.
    it("gives the handler every field", async () => {
      const id = await harness.insertPending({
        source: SOURCE,
        idempotencyKey: "key-1",
        payload: '{"id":"order-1","total":42}',
        aggregateId: "agg-1",
        eventType: "OrderPlaced",
        correlationId: "corr-1",
        attempt: 2,
      });

      let seen: InboxMessage | undefined;

      await runUntil(1, async (message) => {
        seen = message;
      });

      assert.ok(seen);
      assert.equal(seen.id.toLowerCase(), id.toLowerCase());
      assert.equal(seen.source, SOURCE);
      assert.equal(seen.idempotencyKey, "key-1");
      assert.equal(seen.aggregateId, "agg-1");
      assert.equal(seen.eventType, "OrderPlaced");
      assert.equal(seen.correlationId, "corr-1");
      assert.equal(seen.attempt, 2);
      assert.deepEqual(seen.payload, { id: "order-1", total: 42 });
    });

    // Item 1, the nullable fields.
    it("gives the nullable fields as null", async () => {
      await harness.insertPending({ source: SOURCE, idempotencyKey: "key-null", payload: '{"a":1}' });

      let seen: InboxMessage | undefined;

      await runUntil(1, async (message) => {
        seen = message;
      });

      assert.ok(seen);
      assert.equal(seen.aggregateId, null);
      assert.equal(seen.eventType, null);
      assert.equal(seen.correlationId, null);
      assert.equal(seen.attempt, 0);
    });

    // Item 2. This is the whole point of the pull path.
    it("commits the writes and the completion together", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-2", payload: "{}" });

      await runUntil(1, async (_message, transaction) => {
        await writeApplicationRow(transaction, "row-2");
      });

      assert.equal((await harness.readRow(id)).state, "processed");
      assert.equal(await harness.countApplicationRows("row-2"), 1);
    });

    // Item 3.
    it("leaves no write behind when the handler throws", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-3", payload: "{}" });

      await runUntil(1, async (_message, transaction) => {
        await writeApplicationRow(transaction, "row-3");
        throw new Error("the handler failed");
      });

      assert.equal(await harness.countApplicationRows("row-3"), 0);
      assert.notEqual((await harness.readRow(id)).state, "processed");
    });

    // Item 4.
    it("rolls the writes back when the completion affects no row", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-4", payload: "{}" });

      // The lease is long, so no renewal notices the theft. The completion must catch it.
      await runUntil(1, async (message, transaction) => {
        await writeApplicationRow(transaction, "row-4");
        await harness.stealClaim(message.id);
      }, { leaseMs: 600_000 });

      assert.equal(await harness.countApplicationRows("row-4"), 0);
      assert.equal((await harness.readRow(id)).state, "processing");
    });

    // Item 5.
    it("never gives one message to two workers", async () => {
      const total = 20;

      for (let i = 0; i < total; i += 1) {
        await harness.insertPending({ source: SOURCE, idempotencyKey: `key-5-${i}`, payload: "{}" });
      }

      const seen: string[] = [];
      const stop = new AbortController();
      let settle = (): void => undefined;
      const done = new Promise<void>((resolve) => {
        settle = resolve;
      });

      const handle = async (message: InboxMessage): Promise<void> => {
        seen.push(message.id.toLowerCase());

        if (seen.length >= total) {
          settle();
        }
      };

      const first = worker({ batchSize: 3 }).run(handle, stop.signal);
      const second = worker({ batchSize: 3 }).run(handle, stop.signal);

      await done;
      stop.abort();
      await Promise.all([first, second]);

      assert.equal(seen.length, total);
      assert.equal(new Set(seen).size, total);
    });

    // Item 6.
    it("renews and keeps ownership across a slow handler", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-6", payload: "{}" });

      // The lease is one second and the renewal runs every third of it, so a handler of three
      // seconds needs several renewals to survive.
      await runUntil(1, async (_message, transaction) => {
        await new Promise((resolve) => setTimeout(resolve, 3000));
        await writeApplicationRow(transaction, "row-6");
      }, { leaseMs: 1000 });

      assert.equal((await harness.readRow(id)).state, "processed");
      assert.equal(await harness.countApplicationRows("row-6"), 1);
    });

    // Item 7.
    it("aborts the handler and completes nothing when a renewal is lost", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-7", payload: "{}" });

      let aborted = false;

      await runUntil(1, async (message, _transaction, signal) => {
        await harness.stealClaim(message.id);

        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, 30_000);
          signal.addEventListener("abort", () => {
            clearTimeout(timer);
            aborted = true;
            resolve();
          }, { once: true });
        });

        if (aborted) {
          throw new Error("the lease was lost");
        }
      }, { leaseMs: 1000 });

      assert.ok(aborted, "The renewal must abort the handler when the ownership is lost.");
      assert.equal((await harness.readRow(id)).state, "processing");
    });

    // Item 8.
    it("returns an abandoned message after the lease expires", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-8", payload: "{}" });

      const stop = new AbortController();
      let settle = (): void => undefined;
      const reached = new Promise<void>((resolve) => {
        settle = resolve;
      });

      // The shutdown gives no grace at all, which is how a killed worker behaves.
      const run = worker({ leaseMs: 1000, shutdownGraceMs: 0 }).run(async (_message, _transaction, signal) => {
        settle();
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, 300_000);
          signal.addEventListener("abort", () => {
            clearTimeout(timer);
            resolve();
          }, { once: true });
        });
        throw new Error("aborted");
      }, stop.signal);

      await reached;
      stop.abort();
      await run;

      // The message was abandoned, not completed, and the shutdown spent no attempt on it.
      const abandoned = await harness.readRow(id);
      assert.equal(abandoned.state, "processing");
      assert.equal(abandoned.attempt, 0);

      // The lease expires, so a second worker takes the message back.
      let returned = false;
      await runUntil(1, async (message) => {
        returned = message.id.toLowerCase() === id.toLowerCase();
      });

      assert.ok(returned, "The abandoned message must return after the lease expires.");
    });

    // Item 9.
    it("increments the attempt and applies the backoff on a retry", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-9", payload: "{}" });
      const before = await harness.readScheduledAt(id);

      await runUntil(1, async () => {
        throw new Error("no");
      }, { retryPolicy: defaultRetryPolicy({ maxAttempts: 5, baseDelayMs: 60_000, maxDelayMs: 60_000, jitter: 0 }) });

      const row = await harness.readRow(id);

      assert.equal(row.state, "pending");
      assert.equal(row.attempt, 1);
      assert.ok(row.lastError);

      const after_ = await harness.readScheduledAt(id);
      assert.ok(after_.getTime() > before.getTime() + 30_000, "The backoff must move the schedule forward.");
    });

    // Item 10.
    it("reaches the dead letter at the ceiling", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-10", payload: "{}", attempt: 3 });

      await runUntil(1, async () => {
        throw new Error("no");
      }, { retryPolicy: defaultRetryPolicy({ maxAttempts: 3, baseDelayMs: 1000, maxDelayMs: 1000, jitter: 0 }) });

      const row = await harness.readRow(id);

      assert.equal(row.state, "dead");
      assert.ok(row.lastError);
    });

    // Item 11.
    it("changes nothing under a stale token", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-11", payload: "{}" });
      const sql = inboxSql(harness.dialect, defaultSchema);
      const stale = "11111111-2222-3333-4444-555555555555";

      // Another worker owns the message under a token of its own.
      await harness.stealClaim(id);

      const connection = await harness.connections.connect();

      try {
        assert.equal((await connection.query(sql.complete.text, [id, stale])).rowCount, 0);
        assert.equal((await connection.query(sql.retry.text, [1000, "no", id, stale])).rowCount, 0);
        assert.equal((await connection.query(sql.dead.text, ["no", id, stale])).rowCount, 0);
      } finally {
        await connection.release();
      }

      const row = await harness.readRow(id);

      assert.equal(row.state, "processing");
      assert.equal(row.attempt, 0);
      assert.equal(row.lastError, null);
    });

    // Item 12.
    it("claims nothing new after a stop and abandons rather than completes", async () => {
      const first = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-12-a", payload: "{}" });
      const second = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-12-b", payload: "{}" });

      const stop = new AbortController();
      let settle = (): void => undefined;
      const reached = new Promise<void>((resolve) => {
        settle = resolve;
      });

      // A batch of one, so the second message is still pending when the stop arrives.
      const run = worker({ batchSize: 1, leaseMs: 60_000, shutdownGraceMs: 0 })
        .run(async (_message, _transaction, signal) => {
          settle();
          await new Promise<void>((resolve) => {
            const timer = setTimeout(resolve, 300_000);
            signal.addEventListener("abort", () => {
              clearTimeout(timer);
              resolve();
            }, { once: true });
          });
          throw new Error("aborted");
        }, stop.signal);

      await reached;
      stop.abort();
      await run;

      // The two rows carry the same scheduled_at, so the claim order is not decided. The
      // guarantee is about the counts: the stop completes nothing and claims nothing new, so
      // exactly one row is in flight and the other is untouched.
      const states = [(await harness.readRow(first)).state, (await harness.readRow(second)).state];

      assert.ok(!states.includes("processed"), `a row was completed: ${states.join(", ")}`);
      assert.equal(states.filter((state) => state === "processing").length, 1);
      assert.equal(states.filter((state) => state === "pending").length, 1);
    });

    // Item 13.
    it("works against a mapped table and mapped column names", async () => {
      const schema = await harness.createMappedSchema();
      const id = await harness.insertMappedPending(schema, SOURCE, "key-13", '{"a":1}');

      await runUntil(1, async (message, transaction) => {
        assert.equal(message.id.toLowerCase(), id.toLowerCase());
        await writeApplicationRow(transaction, "row-13");
      }, { schema });

      assert.equal(await harness.readMappedState(schema, id), "processed");
      assert.equal(await harness.countApplicationRows("row-13"), 1);
    });

    // Item 15. The failure text reaches the database, so a secret must not travel with it.
    it("keeps a secret out of the last error column", async () => {
      const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-15", payload: "{}" });

      await runUntil(1, async () => {
        throw new Error("connect failed: Host=db;Password=hunter2;Database=queuebox");
      });

      const row = await harness.readRow(id);

      assert.ok(row.lastError);
      assert.doesNotMatch(row.lastError, /hunter2/);
    });

    // Item 16.
    it("does not start a polling storm on an empty claim", async () => {
      // The table is empty, so every connection belongs to a claim.
      let opened = 0;
      const counting = {
        connect: async () => {
          opened += 1;
          return harness.connections.connect();
        },
      };

      const stop = new AbortController();
      const run = new InboxWorker(counting, {
        source: SOURCE,
        dialect: harness.dialect,
        pollIntervalMs: 200,
      }).run(async () => undefined, stop.signal);

      await new Promise((resolve) => setTimeout(resolve, 2000));
      stop.abort();
      await run;

      // Two seconds at two hundred milliseconds is about ten claims. The band is wide, because
      // a container is not a clock.
      assert.ok(opened >= 4 && opened <= 20, `The worker ran ${opened} claims in two seconds.`);
    });

    // Item 17.
    it("never holds more than the batch", async () => {
      const total = 12;

      for (let i = 0; i < total; i += 1) {
        await harness.insertPending({ source: SOURCE, idempotencyKey: `key-17-${i}`, payload: "{}" });
      }

      let inFlight = 0;
      let highWater = 0;

      await runUntil(total, async () => {
        inFlight += 1;
        highWater = Math.max(highWater, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 50));
        inFlight -= 1;
      }, { batchSize: 3 });

      assert.ok(highWater <= 3, `The worker held ${highWater} messages, and the batch is 3.`);
    });
  });
}

contract(new PostgresHarness());
contract(new SqlServerHarness());
