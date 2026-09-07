import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { defaultSchema, inboxSql } from "../../src/sql.ts";

describe("the PostgreSQL statements", () => {
  const sql = inboxSql("postgresql", defaultSchema);

  it("claims with the skip locked contract", () => {
    assert.match(sql.claim.text, /FOR UPDATE SKIP LOCKED/);
    assert.match(sql.claim.text, /FROM "inbox"/);
  });

  it("binds positionally", () => {
    // pg accepts no named parameter, so every statement is positional in both dialects.
    assert.match(sql.claim.text, /\$1/);
    assert.doesNotMatch(sql.claim.text, /:source/);
  });
});

describe("the SQL Server statements", () => {
  const sql = inboxSql("sqlserver", defaultSchema);

  it("claims with the read past contract", () => {
    assert.match(sql.claim.text, /UPDLOCK, READPAST, ROWLOCK/);
    assert.match(sql.claim.text, /TOP \(@p2\)/);
    assert.match(sql.claim.text, /OUTPUT INSERTED\.\*/);
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
