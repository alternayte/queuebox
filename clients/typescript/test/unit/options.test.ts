import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { resolveOptions } from "../../src/options.ts";

describe("the options", () => {
  it("applies the defaults", () => {
    const resolved = resolveOptions({ source: "orders" });

    assert.equal(resolved.batchSize, 10);
    assert.equal(resolved.leaseMs, 30_000);
    assert.equal(resolved.dialect, "postgresql");
    assert.equal(resolved.renewalIntervalMs, 10_000);
  });

  it("never lets the concurrency pass the batch", () => {
    assert.equal(resolveOptions({ source: "orders", batchSize: 4, maxConcurrency: 16 }).concurrency, 4);
    assert.equal(resolveOptions({ source: "orders", batchSize: 4 }).concurrency, 4);
    assert.equal(resolveOptions({ source: "orders", batchSize: 4, maxConcurrency: 2 }).concurrency, 2);
  });

  it("demands a source", () => {
    assert.throws(() => resolveOptions({ source: "" }), /source/);
    assert.throws(() => resolveOptions({ source: "   " }), /source/);
  });

  it("rejects a number that cannot work", () => {
    assert.throws(() => resolveOptions({ source: "orders", batchSize: 0 }), /batchSize/);
    // The renewal runs every third of the lease, so a lease under three has no interval.
    assert.throws(() => resolveOptions({ source: "orders", leaseMs: 2 }), /leaseMs/);
    assert.throws(() => resolveOptions({ source: "orders", pollIntervalMs: 0 }), /pollIntervalMs/);
    assert.throws(() => resolveOptions({ source: "orders", maxConcurrency: 0 }), /maxConcurrency/);
  });
});
