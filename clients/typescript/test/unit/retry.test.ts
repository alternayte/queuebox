import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { deadLetter, defaultRetryPolicy, retryAfter } from "../../src/retry.ts";
import type { InboxMessage } from "../../src/message.ts";

function message(attempt: number): InboxMessage {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    source: "orders",
    idempotencyKey: "key-1",
    aggregateId: null,
    eventType: null,
    payload: {},
    attempt,
    correlationId: null,
  };
}

const failure = new Error("no");

describe("the default retry policy", () => {
  it("retries while the attempt is below the ceiling", () => {
    const policy = defaultRetryPolicy({ maxAttempts: 3, baseDelayMs: 1000, maxDelayMs: 60_000, jitter: 0 });

    for (const attempt of [0, 1, 2]) {
      assert.equal(policy(message(attempt), failure).retry, true);
    }
  });

  it("dead-letters at the ceiling", () => {
    const policy = defaultRetryPolicy({ maxAttempts: 3, baseDelayMs: 1000, maxDelayMs: 60_000, jitter: 0 });

    assert.deepEqual(policy(message(3), failure), deadLetter());
    assert.deepEqual(policy(message(9), failure), deadLetter());
  });

  it("doubles the backoff and then stops", () => {
    const policy = defaultRetryPolicy({ maxAttempts: 10, baseDelayMs: 1000, maxDelayMs: 4000, jitter: 0 });

    assert.deepEqual(policy(message(0), failure), retryAfter(1000));
    assert.deepEqual(policy(message(1), failure), retryAfter(2000));
    assert.deepEqual(policy(message(2), failure), retryAfter(4000));
    assert.deepEqual(policy(message(3), failure), retryAfter(4000));
  });

  it("keeps the jitter inside its band", () => {
    const policy = defaultRetryPolicy({ maxAttempts: 10, baseDelayMs: 10_000, maxDelayMs: 60_000, jitter: 0.2 });

    for (let i = 0; i < 200; i += 1) {
      const action = policy(message(0), failure);

      assert.ok(action.retry);
      assert.ok(action.delayMs >= 8000 && action.delayMs <= 12_000, `the delay ${action.delayMs} left the band`);
    }
  });

  it("does not overflow on a large attempt", () => {
    const policy = defaultRetryPolicy({ maxAttempts: Number.MAX_SAFE_INTEGER, baseDelayMs: 1000, maxDelayMs: 300_000, jitter: 0 });

    assert.deepEqual(policy(message(1_000_000), failure), retryAfter(300_000));
  });

  it("rejects a setting that cannot work", () => {
    assert.throws(() => defaultRetryPolicy({ maxAttempts: 0 }), /maxAttempts/);
    assert.throws(() => defaultRetryPolicy({ jitter: 2 }), /jitter/);
    assert.throws(() => defaultRetryPolicy({ baseDelayMs: 10, maxDelayMs: 5 }), /maxDelayMs/);
  });
});
