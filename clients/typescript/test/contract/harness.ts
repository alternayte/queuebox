import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import type { InboxConnectionSource, InboxSchema, SqlDialect } from "../../src/index.ts";

/**
 * One database under test. The contract tests are written once and run against every harness,
 * because a difference between two dialects must never be a difference of guarantee.
 */
export interface DatabaseHarness {
  readonly dialect: SqlDialect;
  readonly connections: InboxConnectionSource;
  /** The statement a handler runs to write into the application's own table. */
  readonly insertApplicationRow: string;
  start(): Promise<void>;
  stop(): Promise<void>;
  reset(): Promise<void>;
  insertPending(row: PendingRow): Promise<string>;
  readRow(id: string): Promise<{ state: string; attempt: number; lastError: string | null }>;
  readScheduledAt(id: string): Promise<Date>;
  stealClaim(id: string): Promise<void>;
  countApplicationRows(key: string): Promise<number>;
  createMappedSchema(): Promise<InboxSchema>;
  insertMappedPending(schema: InboxSchema, source: string, key: string, payload: string): Promise<string>;
  readMappedState(schema: InboxSchema, id: string): Promise<string>;
}

export interface PendingRow {
  readonly source: string;
  readonly idempotencyKey: string;
  readonly payload: string;
  readonly aggregateId?: string | null;
  readonly eventType?: string | null;
  readonly correlationId?: string | null;
  readonly attempt?: number;
}

/**
 * Reads the QueueBox migrations from the repository.
 * The tests apply the real schema, so the library cannot drift away from it.
 */
export function migrations(dialectDirectory: "postgresql" | "sqlserver"): string[] {
  const here = dirname(fileURLToPath(import.meta.url));
  const root = join(here, "..", "..", "..", "..");
  const module = dialectDirectory === "postgresql" ? "postgres" : "sqlserver";
  const directory = join(root, module, "src", "main", "resources", "db", dialectDirectory);

  return readdirSync(directory)
    .filter((name) => name.startsWith("V") && name.endsWith(".sql"))
    .sort((left, right) => version(left) - version(right))
    .map((name) => readFileSync(join(directory, name), "utf8"));
}

function version(name: string): number {
  const match = /^V(\d+)__/.exec(name);
  return match?.[1] === undefined ? 0 : Number(match[1]);
}

/** The mapped names that item 13 uses. Both harnesses create the same shape. */
export function mappedSchema(base: InboxSchema): InboxSchema {
  return {
    ...base,
    table: "qb_messages",
    id: "message_id",
    state: "row_state",
    source: "channel",
    payload: "body",
    idempotencyKey: "dedup_key",
    claimToken: "lease_token",
    attempt: "tries",
  };
}
