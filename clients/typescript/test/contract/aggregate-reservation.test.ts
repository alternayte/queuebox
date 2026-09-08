import { after, before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";

import { InboxWorker } from "../../src/index.ts";
import type { DatabaseHarness } from "./harness.ts";
import { PostgresHarness } from "./postgres-harness.ts";
import { SqlServerHarness } from "./sqlserver-harness.ts";

const SOURCE = "orders";

/**
 * Item 18 of clients/contract-tests.md, from finding F-087. The claim must hold at most one
 * message per aggregate in flight, even under two competing workers.
 */
function aggregateReservation(harness: DatabaseHarness): void {
  describe(`aggregate reservation against ${harness.dialect}`, () => {
    before(async () => {
      await harness.start();
    });

    after(async () => {
      await harness.stop();
    });

    beforeEach(async () => {
      await harness.reset();
    });

    function worker(): InboxWorker {
      return new InboxWorker(harness.connections, {
        source: SOURCE,
        dialect: harness.dialect,
        pollIntervalMs: 50,
        shutdownGraceMs: 10_000,
        batchSize: 4,
      });
    }

    /** Run two workers until `expected` handler calls finish, then stop both. */
    async function runTwoWorkersUntil(expected: number, handler: () => Promise<void>): Promise<void> {
      const stop = new AbortController();
      let handled = 0;
      let settle = (): void => undefined;
      const reached = new Promise<void>((resolve) => {
        settle = resolve;
      });

      const wrapped = async (): Promise<void> => {
        try {
          await handler();
        } finally {
          handled += 1;

          if (handled >= expected) {
            settle();
          }
        }
      };

      const runs = [worker().run(wrapped, stop.signal), worker().run(wrapped, stop.signal)];

      await reached;
      // The failure path runs after the handler returned, so give it room to finish.
      await new Promise((resolve) => setTimeout(resolve, 500));
      stop.abort();
      await Promise.all(runs);
    }

    // Item 18, one aggregate.
    it("never runs two handlers of one aggregate at one time", async () => {
      for (let i = 0; i < 4; i += 1) {
        await harness.insertPending({
          source: SOURCE,
          idempotencyKey: `key-18-single-${i}`,
          payload: "{}",
          aggregateId: "agg-1",
        });
      }

      const windows: Array<[number, number]> = [];

      await runTwoWorkersUntil(4, async () => {
        const start = Date.now();
        await new Promise((resolve) => setTimeout(resolve, 200));
        windows.push([start, Date.now()]);
      });

      assert.equal(windows.length, 4, "the handler must run exactly four times");

      windows.sort((a, b) => a[0] - b[0]);

      for (let i = 1; i < windows.length; i += 1) {
        assert.ok(
          windows[i][0] >= windows[i - 1][1],
          `handler ${i} started at ${windows[i][0]}, before handler ${i - 1} ended at ${windows[i - 1][1]}`,
        );
      }
    });

    // Item 18, two aggregates.
    it("does run two aggregates at one time", async () => {
      for (let i = 0; i < 2; i += 1) {
        await harness.insertPending({
          source: SOURCE,
          idempotencyKey: `key-18-multi-a-${i}`,
          payload: "{}",
          aggregateId: "agg-1",
        });
        await harness.insertPending({
          source: SOURCE,
          idempotencyKey: `key-18-multi-b-${i}`,
          payload: "{}",
          aggregateId: "agg-2",
        });
      }

      let inFlight = 0;
      let peak = 0;

      await runTwoWorkersUntil(4, async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 200));
        inFlight -= 1;
      });

      assert.equal(peak, 2, "the peak concurrency must be 2, one per aggregate");
    });
  });
}

aggregateReservation(new PostgresHarness());
aggregateReservation(new SqlServerHarness());
