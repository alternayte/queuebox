/** The database dialect that the inbox table lives in. */
export type SqlDialect = "postgresql" | "sqlserver";

/**
 * The name of the inbox table and of every column the library reads or writes.
 * QueueBox lets an operator map these names, so the library must not assume the defaults.
 */
export interface InboxSchema {
  readonly table: string;
  readonly id: string;
  readonly consumption: string;
  readonly source: string;
  readonly state: string;
  readonly scheduledAt: string;
  readonly createdAt: string;
  readonly claimToken: string;
  readonly claimedAt: string;
  readonly leaseExpiresAt: string;
  readonly processedAt: string;
  readonly attempt: string;
  readonly lastError: string;
  readonly idempotencyKey: string;
  readonly aggregateId: string;
  readonly eventType: string;
  readonly payload: string;
  readonly correlationId: string;
}

/** The QueueBox V6 names. */
export const defaultSchema: InboxSchema = Object.freeze({
  table: "inbox",
  id: "id",
  consumption: "consumption",
  source: "source",
  state: "state",
  scheduledAt: "scheduled_at",
  createdAt: "created_at",
  claimToken: "claim_token",
  claimedAt: "claimed_at",
  leaseExpiresAt: "lease_expires_at",
  processedAt: "processed_at",
  attempt: "attempt",
  lastError: "last_error",
  idempotencyKey: "idempotency_key",
  aggregateId: "aggregate_id",
  eventType: "event_type",
  payload: "payload",
  correlationId: "correlation_id",
});

/**
 * One statement, with the order of its parameters.
 *
 * Both dialects bind POSITIONALLY, because pg accepts no named parameter. PostgreSQL writes
 * `$1` and SQL Server writes `@p1`, and one connection interface therefore serves both drivers.
 */
export interface InboxStatement {
  readonly text: string;
  /** The parameters, in the order the text names them. */
  readonly params: readonly string[];
}

/** The five statements of the pull contract, for one dialect and one schema. */
export interface InboxStatements {
  /** Takes rows. Parameters: source, batch, leaseMs. */
  readonly claim: InboxStatement;
  /** Extends the lease. Parameters: leaseMs, id, token. */
  readonly renew: InboxStatement;
  /** Marks the row processed. Parameters: id, token. */
  readonly complete: InboxStatement;
  /** Returns the row to pending. Parameters: delayMs, error, id, token. */
  readonly retry: InboxStatement;
  /** Marks the row dead. Parameters: error, id, token. */
  readonly dead: InboxStatement;
}

// An identifier is quoted before it reaches the database, and it is also checked here.
const SAFE_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_$]{0,62}$/;

function validate(schema: InboxSchema): void {
  for (const [property, value] of Object.entries(schema)) {
    if (typeof value !== "string" || !SAFE_IDENTIFIER.test(value)) {
      throw new TypeError(`The schema name '${property}' is not a plain SQL identifier.`);
    }
  }
}

/**
 * Render the five statements.
 *
 * @param dialect the database dialect
 * @param schema the table and column names
 * @returns the statements, with every identifier quoted
 */
export function inboxSql(dialect: SqlDialect, schema: InboxSchema): InboxStatements {
  validate(schema);
  return dialect === "postgresql" ? postgresql(schema) : sqlserver(schema);
}

function postgresql(s: InboxSchema): InboxStatements {
  const q = (name: string): string => `"${name}"`;
  const fence = (id: string, token: string): string => `
WHERE ${q(s.id)} = ${id} AND ${q(s.consumption)} = 'pull' AND ${q(s.state)} = 'processing'
  AND ${q(s.claimToken)} = ${token} AND ${q(s.leaseExpiresAt)} > clock_timestamp()`;

  return {
    claim: {
      text: `
WITH candidates AS (
    SELECT ${q(s.id)} FROM ${q(s.table)}
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = $1
      AND ((${q(s.state)} = 'pending' AND ${q(s.scheduledAt)} <= clock_timestamp())
        OR (${q(s.state)} = 'processing' AND ${q(s.leaseExpiresAt)} <= clock_timestamp()))
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
UPDATE ${q(s.table)} AS target
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = gen_random_uuid(),
    ${q(s.claimedAt)} = clock_timestamp(),
    ${q(s.leaseExpiresAt)} = clock_timestamp() + $3 * INTERVAL '1 millisecond'
FROM candidates WHERE target.${q(s.id)} = candidates.${q(s.id)}
RETURNING target.*`,
      params: ["source", "batch", "leaseMs"],
    },
    renew: {
      text: `UPDATE ${q(s.table)} SET ${q(s.leaseExpiresAt)} = clock_timestamp() + $1 * INTERVAL '1 millisecond'${fence("$2", "$3")}`,
      params: ["leaseMs", "id", "token"],
    },
    complete: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'processed', ${q(s.processedAt)} = clock_timestamp(), ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$1", "$2")}`,
      params: ["id", "token"],
    },
    retry: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'pending', ${q(s.scheduledAt)} = clock_timestamp() + $1 * INTERVAL '1 millisecond', ${q(s.attempt)} = ${q(s.attempt)} + 1, ${q(s.lastError)} = $2, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$3", "$4")}`,
      params: ["delayMs", "error", "id", "token"],
    },
    dead: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'dead', ${q(s.lastError)} = $1, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$2", "$3")}`,
      params: ["error", "id", "token"],
    },
  };
}

function sqlserver(s: InboxSchema): InboxStatements {
  const q = (name: string): string => `[${name}]`;
  const fence = (id: string, token: string): string => `
WHERE ${q(s.id)} = ${id} AND ${q(s.consumption)} = 'pull' AND ${q(s.state)} = 'processing'
  AND ${q(s.claimToken)} = ${token} AND ${q(s.leaseExpiresAt)} > SYSUTCDATETIME()`;

  return {
    claim: {
      text: `
WITH candidates AS (
    SELECT TOP (@p2) * FROM ${q(s.table)} WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = @p1
      AND ((${q(s.state)} = 'pending' AND ${q(s.scheduledAt)} <= SYSUTCDATETIME())
        OR (${q(s.state)} = 'processing' AND ${q(s.leaseExpiresAt)} <= SYSUTCDATETIME()))
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}
)
UPDATE candidates
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = NEWID(), ${q(s.claimedAt)} = SYSUTCDATETIME(),
    ${q(s.leaseExpiresAt)} = DATEADD(millisecond, @p3, SYSUTCDATETIME())
OUTPUT INSERTED.*`,
      params: ["source", "batch", "leaseMs"],
    },
    renew: {
      text: `UPDATE ${q(s.table)} SET ${q(s.leaseExpiresAt)} = DATEADD(millisecond, @p1, SYSUTCDATETIME())${fence("@p2", "@p3")}`,
      params: ["leaseMs", "id", "token"],
    },
    complete: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'processed', ${q(s.processedAt)} = SYSUTCDATETIME(), ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p1", "@p2")}`,
      params: ["id", "token"],
    },
    retry: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'pending', ${q(s.scheduledAt)} = DATEADD(millisecond, @p1, SYSUTCDATETIME()), ${q(s.attempt)} = ${q(s.attempt)} + 1, ${q(s.lastError)} = @p2, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p3", "@p4")}`,
      params: ["delayMs", "error", "id", "token"],
    },
    dead: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'dead', ${q(s.lastError)} = @p1, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p2", "@p3")}`,
      params: ["error", "id", "token"],
    },
  };
}
