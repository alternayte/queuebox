import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { MAX_ERROR_LENGTH, sanitize, sanitizeError } from "../../src/sanitizer.ts";

describe("the sanitiser", () => {
  const secrets: ReadonlyArray<readonly [string, string]> = [
    // The connection string of each driver. The value ends at the semicolon.
    ["Host=db;Username=app;Password=hunter2;Database=queuebox", "hunter2"],
    ["Server=db;User Id=sa;Password=P@ssw0rd!;Encrypt=True", "P@ssw0rd!"],
    ["Server=db;pwd=hunter2;", "hunter2"],
    // The environment and the query notation.
    ["PGPASSWORD=hunter2 psql failed", "hunter2"],
    ["connect failed: password: my secret pass", "my secret pass"],
    // A URL that carries the credential in the user information.
    ["postgres://app:hunter2@db:5432/queuebox refused", "hunter2"],
    ["amqp://user:pass word@rabbit", "pass word"],
    // A bare authentication scheme.
    ["Authorization: Bearer eyJhbGciOiJIUzI1NiJ9", "eyJhbGciOiJIUzI1NiJ9"],
  ];

  for (const [text, secret] of secrets) {
    it(`removes the secret from ${JSON.stringify(text)}`, () => {
      assert.doesNotMatch(sanitize(text) ?? "", new RegExp(secret.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    });
  }

  it("keeps the host and the port", () => {
    assert.match(sanitize("postgres://app:hunter2@db:5432/queuebox refused") ?? "", /db:5432/);
  });

  it("keeps an ordinary word", () => {
    assert.equal(sanitize("the token bucket is empty"), "the token bucket is empty");
    assert.equal(sanitize("Digest authentication failed"), "Digest authentication failed");
  });

  it("truncates a long text", () => {
    const result = sanitize("x".repeat(5000)) ?? "";

    assert.equal(result.length, MAX_ERROR_LENGTH);
    assert.match(result, /\.\.\.\[truncated\]$/);
  });

  it("keeps undefined as undefined", () => {
    assert.equal(sanitize(undefined), undefined);
  });
});

describe("the error sanitiser", () => {
  it("redacts the whole cause chain", () => {
    // A driver puts the connection string in the message of the cause, not of the wrapper.
    const cause = new Error("Host=db;Password=hunter2");
    const error = new Error("the claim failed", { cause });

    const result = sanitizeError(error) ?? "";

    assert.doesNotMatch(result, /hunter2/);
    assert.match(result, /the claim failed/);
    assert.match(result, /Error/);
    assert.match(result, /caused by/);
  });

  it("survives a cause that points at itself", () => {
    const error = new Error("outer");
    (error as { cause?: unknown }).cause = error;

    assert.match(sanitizeError(error) ?? "", /outer/);
  });

  it("names a value that is not an error", () => {
    assert.match(sanitizeError("just a string") ?? "", /just a string/);
  });
});
