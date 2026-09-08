import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { defaultSchema, inboxSql } from "../../src/sql.ts";

/** Count non-overlapping matches of `pattern` (given without a `g` flag) in `text`. */
function occurrences(text: string, pattern: RegExp): number {
  return (text.match(new RegExp(pattern.source, "g")) ?? []).length;
}

describe("the PostgreSQL statements", () => {
  const sql = inboxSql("postgresql", defaultSchema);

  it("claims with the skip locked contract", () => {
    assert.match(sql.claim.text, /FOR UPDATE SKIP LOCKED/);
    assert.match(sql.claim.text, /FROM "inbox"/);
  });

  it("binds positionally, and binds a fourth candLimit parameter", () => {
    // pg accepts no named parameter, so every statement is positional in both dialects.
    assert.match(sql.claim.text, /\$1/);
    assert.match(sql.claim.text, /\$4/);
    assert.doesNotMatch(sql.claim.text, /:source/);
    assert.deepEqual(sql.claim.params, ["source", "batch", "leaseMs", "candLimit"]);
  });

  it("scans candidates in two bounded branches joined by UNION ALL", () => {
    // Deleting the UNION ALL collapses the claim to one branch and drops either the pending
    // scan or the lease-expiry scan.
    assert.equal(occurrences(sql.claim.text, /UNION ALL/), 1);
  });

  it("repeats the full readiness disjunction in the locking read and the final UPDATE", () => {
    // The repetition restores the EvalPlanQual re-check. Without it, two workers can claim the
    // same row: deleting one occurrence must fail this assertion.
    assert.equal(occurrences(sql.claim.text, /OR \(/), 2);
  });

  it("scopes the busy check to (source, aggregate_id) at every occurrence", () => {
    // One busy-aggregate EXISTS check per branch: the eligible CTE, the locked CTE and the
    // final UPDATE. Dropping the source term from any one of them narrows the busy check to
    // aggregate_id alone, which leaks across sources.
    assert.equal(occurrences(sql.claim.text, /busy\."source" = \$1/), 3);
  });

  it("orders every candidate scan by a total order ending in id", () => {
    assert.equal(occurrences(sql.claim.text, /ORDER BY "scheduled_at", "created_at", "id"/), 3);
  });
});

describe("the SQL Server statements", () => {
  const sql = inboxSql("sqlserver", defaultSchema);

  it("claims with the read past contract", () => {
    assert.match(sql.claim.text, /UPDLOCK, READPAST, ROWLOCK/);
    assert.match(sql.claim.text, /TOP \(@qbCandLimit\)/);
    assert.match(sql.claim.text, /TOP \(@qbBatch\)/);
    assert.match(sql.claim.text, /OUTPUT inserted\.\*/);
  });

  it("binds a fourth candLimit parameter", () => {
    assert.deepEqual(sql.claim.params, ["source", "batch", "leaseMs", "candLimit"]);
  });

  it("scans candidates in two bounded branches joined by UNION ALL", () => {
    assert.equal(occurrences(sql.claim.text, /UNION ALL/), 1);
  });

  it("repeats the readiness predicate in the final UPDATE", () => {
    assert.equal(occurrences(sql.claim.text, /OR \(/), 1);
  });

  it("scopes the busy check to (source, aggregate_id) at every occurrence", () => {
    assert.equal(occurrences(sql.claim.text, /busy\.\[source\] = @src/), 2);
  });

  it("declares its T-SQL locals under names distinct from the bound parameters", () => {
    // A DECLARE that reuses a bound parameter name raises Msg 134 on every call.
    for (const local of ["@qbBatch", "@qbLeaseMs", "@qbCandLimit", "@src"]) {
      assert.match(sql.claim.text, new RegExp(`DECLARE ${local} `));
    }

    assert.doesNotMatch(sql.claim.text, /DECLARE @p1/);
    assert.doesNotMatch(sql.claim.text, /DECLARE @p2/);
    assert.doesNotMatch(sql.claim.text, /DECLARE @p3/);
    assert.doesNotMatch(sql.claim.text, /DECLARE @p4/);
  });

  it("serializes the claim per source through sp_getapplock and THROWs on a negative result", () => {
    assert.match(sql.claim.text, /sp_getapplock @Resource = @src/);
    assert.match(sql.claim.text, /IF @lockresult < 0/);
    assert.match(sql.claim.text, /THROW 51000/);
  });

  it("carries its own transaction control around the claim", () => {
    assert.match(sql.claim.text, /^\s*BEGIN TRANSACTION;/);
    assert.match(sql.claim.text, /COMMIT TRANSACTION;\s*$/);
    assert.match(sql.claim.text, /ROLLBACK TRANSACTION;/);
  });

  it("orders every candidate scan by a total order ending in id", () => {
    assert.equal(occurrences(sql.claim.text, /ORDER BY \[scheduled_at\], \[created_at\], \[id\]/), 3);
  });
});

describe("every statement after the claim", () => {
  for (const dialect of ["postgresql", "sqlserver"] as const) {
    it(`fences on the token in ${dialect}`, () => {
      const sql = inboxSql(dialect, defaultSchema);

      for (const statement of [sql.renew, sql.complete, sql.retry, sql.dead]) {
        assert.match(statement.text, /'pull'/);
        assert.match(statement.text, /'processing'/);
      }
    });
  }
});

describe("the schema mapping", () => {
  it("renames the table and the column", () => {
    const sql = inboxSql("postgresql", { ...defaultSchema, table: "qb_messages", state: "row_state" });

    assert.match(sql.complete.text, /"qb_messages"/);
    assert.match(sql.complete.text, /"row_state" = 'processed'/);
    assert.doesNotMatch(sql.complete.text, /"inbox"/);
  });

  for (const name of ["inbox; DROP TABLE users", 'in"box', "in]box", ""]) {
    it(`rejects the unsafe name ${JSON.stringify(name)}`, () => {
      // A mapping comes from configuration, and configuration is not a trusted SQL fragment.
      assert.throws(() => inboxSql("postgresql", { ...defaultSchema, table: name }), /identifier/);
    });
  }
});
