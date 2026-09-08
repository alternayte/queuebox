import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import mssql from "mssql";

import { InboxWorker } from "../../src/index.ts";
import type { InboxLogger } from "../../src/index.ts";
import { SqlServerHarness } from "./sqlserver-harness.ts";

const SOURCE = "locked-source";

/**
 * Item 19 of clients/contract-tests.md, from finding F-087. A claim lock failure (Msg 51000,
 * from sp_getapplock returning negative) is a transient failure of the claim call, never a
 * failure of a message.
 *
 * COVERAGE NOTE: this test proves the "never mark failed or dead" half of item 19, and that the
 * worker survives past the 30 second sp_getapplock timeout without crashing. It does NOT prove
 * the "must not retry immediately" half. Every claim attempt already blocks for the whole 30
 * second sp_getapplock timeout before it fails, so the gap between two failing claims is large
 * whether or not the worker adds a poll-interval backoff on top of it: the two cases are not
 * distinguishable from outside with the fixed 30 second lock timeout the canonical statement
 * declares. Proving the backoff itself needs a shorter, configurable lock timeout, which the
 * canonical SQL Server claim text does not expose as a parameter.
 */
describe("a SQL Server claim lock failure", () => {
  const harness = new SqlServerHarness();

  before(async () => {
    await harness.start();
  });

  after(async () => {
    await harness.stop();
  });

  it("is treated as transient, never as a message failure", async () => {
    await harness.reset();

    const id = await harness.insertPending({ source: SOURCE, idempotencyKey: "key-19", payload: "{}" });

    // Hold the per-source applock from outside the worker, for a session, with no timeout. Every
    // claim on this source must wait out the worker's own 30 second lock timeout and then see
    // sp_getapplock return negative.
    const holder = new mssql.Transaction(harness.rawPool);
    await holder.begin();
    const acquire = new mssql.Request(holder);
    acquire.input("src", SOURCE);
    await acquire.query(
      "EXEC sp_getapplock @Resource = @src, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = -1;",
    );

    let claimFailures = 0;
    const logger: InboxLogger = {
      info: () => undefined,
      warn: () => undefined,
      error: (message) => {
        if (message === "The claim failed.") {
          claimFailures += 1;
        }
      },
    };

    const worker = new InboxWorker(harness.connections, {
      source: SOURCE,
      dialect: "sqlserver",
      pollIntervalMs: 200,
      logger,
    });

    const stop = new AbortController();
    const run = worker.run(async () => undefined, stop.signal);

    try {
      // Longer than the worker's 30 second sp_getapplock timeout: the message must still be
      // untouched, and the worker must still be running, having backed off rather than crashed.
      await new Promise((resolve) => setTimeout(resolve, 35_000));

      const duringLock = await harness.readRow(id);
      assert.equal(duringLock.state, "pending");
      assert.equal(duringLock.attempt, 0);
      assert.equal(duringLock.lastError, null);
      assert.ok(claimFailures > 0, "the claim must have reported at least one transient failure while the lock was held");

      // Release the lock. The worker did not crash and it did not retry immediately into the
      // lock, so the very next poll must succeed.
      const release = new mssql.Request(holder);
      release.input("src", SOURCE);
      await release.query("EXEC sp_releaseapplock @Resource = @src, @LockOwner = 'Session';");
      await holder.rollback();

      const deadline = Date.now() + 30_000;
      let after_: { state: string; attempt: number; lastError: string | null } = duringLock;

      while (Date.now() < deadline) {
        after_ = await harness.readRow(id);

        if (after_.state === "processed") {
          break;
        }

        await new Promise((resolve) => setTimeout(resolve, 200));
      }

      assert.equal(after_.state, "processed");
      assert.equal(after_.attempt, 0, "a lock failure must not spend an attempt");
      assert.equal(after_.lastError, null);
    } finally {
      stop.abort();
      await run;
    }
  });
});
