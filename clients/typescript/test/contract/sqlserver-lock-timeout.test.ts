import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import mssql from "mssql";

import { defaultSchema, inboxSql } from "../../src/index.ts";
import { SqlServerHarness } from "./sqlserver-harness.ts";

const SOURCE = "lock-timeout-source";

/**
 * Regression test for finding F-087. The canonical claim (examples/pull/sql/sqlserver/claim.sql)
 * sets sp_getapplock's own @LockTimeout. That value must sit below every driver default, so the
 * server always raises Msg 51000 before a driver aborts the call on its own. A client-side abort
 * does NOT roll back the server-side transaction: the lock uses @LockOwner = 'Transaction', so an
 * abandoned transaction keeps the per-source claim lock until the connection resets, and every
 * later claim on that source then waits its own full timeout and aborts the same way.
 *
 * mssql defaults its request timeout to 15 seconds. This test opens a dedicated pool with a 20
 * second request timeout instead: below the 30 second minimum this fix requires callers to set,
 * and (before the fix) below the canonical statement's own 30 second lock timeout, so a pre-fix
 * run always sees the driver's own request timeout fire first with no Msg 51000 in sight.
 */
describe("the SQL Server lock timeout", () => {
  const harness = new SqlServerHarness();
  let shortPool: mssql.ConnectionPool;

  before(async () => {
    await harness.start();
    shortPool = await new mssql.ConnectionPool(`${harness.connectionUri};Request Timeout=20000`).connect();
  });

  after(async () => {
    await shortPool.close();
    await harness.stop();
  });

  it("fires before a 20 second driver request timeout", async () => {
    await harness.reset();

    // Hold the per-source applock from outside the worker, for a session, with no timeout.
    const holder = new mssql.Request(harness.rawPool);
    holder.input("src", SOURCE);
    await holder.query(
      "EXEC sp_getapplock @Resource = @src, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = -1;",
    );

    const statements = inboxSql("sqlserver", defaultSchema);

    const claim = new mssql.Request(shortPool);
    claim.input("p1", SOURCE);
    claim.input("p2", 10);
    claim.input("p3", 30000);
    claim.input("p4", 100);

    const started = Date.now();
    let failure: unknown;

    try {
      await claim.query(statements.claim.text);
    } catch (error) {
      failure = error;
    }

    const elapsedMs = Date.now() - started;

    const release = new mssql.Request(harness.rawPool);
    release.input("src", SOURCE);
    await release.query("EXEC sp_releaseapplock @Resource = @src, @LockOwner = 'Session';");

    assert.ok(failure, "the claim must fail while another session holds the per-source applock");
    const message = String((failure as Error).message ?? failure);
    const number = (failure as { number?: unknown }).number;

    assert.ok(
      number === 51000,
      `the claim failed with number ${String(number)} instead of Msg 51000, after ${elapsedMs}ms: ${message}`,
    );
    assert.ok(
      !/timeout/i.test(message),
      `the claim's error message reads as a driver timeout rather than a Msg 51000 THROW: ${message}`,
    );
    assert.ok(
      elapsedMs < 15_000,
      `the claim took ${elapsedMs}ms to fail, too close to the 20 second request timeout for the 10 second server-side lock timeout to have fired first`,
    );
  });
});
