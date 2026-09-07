import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { MAX_ERROR_LENGTH, sanitize } from "../../src/sanitizer.ts";

interface RedactionCase {
  readonly name: string;
  readonly input: string;
  readonly expected?: string;
  readonly mustNotContain?: readonly string[];
  readonly mustContain?: readonly string[];
}

interface RedactionCorpus {
  readonly maxLength: number;
  readonly truncationMarker: string;
  readonly cases: readonly RedactionCase[];
}

function loadCorpus(): RedactionCorpus {
  // The corpus sits beside the libraries, because it belongs to all of them.
  const here = dirname(fileURLToPath(import.meta.url));
  const path = join(here, "..", "..", "..", "redaction-corpus.json");

  return JSON.parse(readFileSync(path, "utf8")) as RedactionCorpus;
}

/**
 * Runs the shared redaction corpus. See `clients/redaction-corpus.json`.
 *
 * QueueBox and every client library redact the same text in the same way. A library that redacts
 * almost the same as the others is a security defect, not a difference of idiom, so the cases
 * live in one file that all of them read.
 */
describe("the shared redaction corpus", () => {
  const corpus = loadCorpus();

  it("states the same maximum length as the sanitiser", () => {
    assert.equal(corpus.maxLength, MAX_ERROR_LENGTH);
  });

  it("holds cases", () => {
    assert.ok(corpus.cases.length > 0);
  });

  for (const one of corpus.cases) {
    it(one.name, () => {
      const got = sanitize(one.input) ?? "";

      if (one.expected !== undefined) {
        assert.equal(got, one.expected);
      }

      for (const secret of one.mustNotContain ?? []) {
        assert.ok(!got.includes(secret), `the secret ${JSON.stringify(secret)} printed in ${JSON.stringify(got)}`);
      }

      for (const kept of one.mustContain ?? []) {
        assert.ok(got.includes(kept), `the text ${JSON.stringify(kept)} was lost from ${JSON.stringify(got)}`);
      }
    });
  }

  it("stays linear in the length of the text", () => {
    // The key prefix and the scheme name were unbounded, so the patterns were quadratic and a
    // five thousand character message cost about one hundred and eighty milliseconds. The
    // redaction runs on every failure, and the length of an error text is not ours to choose, so
    // the cost was also a denial of service. This bound is generous: the quadratic version
    // needed minutes.
    const text = `amqp://user:aa  bb@rabbit:5672/vh ${"x".repeat(50_000)}`;

    const started = performance.now();
    sanitize(text);
    const elapsed = performance.now() - started;

    assert.ok(elapsed < 5000, `the redaction took ${elapsed.toFixed(0)} ms, which means it is quadratic again`);
  });
});
