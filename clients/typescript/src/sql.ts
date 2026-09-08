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
  /**
   * Takes rows. Parameters: source, batch, leaseMs, candLimit.
   *
   * On SQL Server the text carries its own transaction control (BEGIN TRANSACTION and COMMIT
   * TRANSACTION) and it must own its transaction scope: a caller must run it alone, never nested
   * inside a wider transaction. A wider transaction leaves the claimed rows uncommitted until the
   * caller's own commit, lets a lock failure destroy the caller's whole transaction instead of
   * only the claim, and holds the per-source applock open for as long as the caller's own
   * transaction stays open.
   */
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
 * A caller who runs the SQL Server claim by hand must run it alone, never nested inside a wider
 * transaction. See the `claim` field of {@link InboxStatements} for the consequences of nesting
 * it.
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

  // The claim takes at most one message per aggregate. A row whose aggregate already holds a
  // message in state 'processing' under a live lease is not a candidate, and two rows of one
  // aggregate never leave one claim together. The full readiness predicate repeats in the
  // locking read and again in the final UPDATE, which restores the EvalPlanQual re-check:
  // without it, two workers can claim the same row. The text follows
  // examples/pull/sql/postgresql/claim.sql exactly; only the identifiers and the parameter
  // placeholders move. $1 is source, $2 is batch, $3 is leaseMs, $4 is candLimit.
  const busy = (alias: string): string => `
            SELECT 1 FROM ${q(s.table)} AS busy
            WHERE busy.${q(s.aggregateId)} = ${alias}.${q(s.aggregateId)}
              AND busy.${q(s.source)} = $1
              AND busy.${q(s.consumption)} = 'pull'
              AND busy.${q(s.state)} = 'processing'
              AND busy.${q(s.leaseExpiresAt)} > clock_timestamp()`;

  return {
    claim: {
      text: `
WITH candidates AS (
    (
        SELECT ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)}
        FROM ${q(s.table)}
        WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = $1 AND ${q(s.state)} = 'pending'
          AND ${q(s.scheduledAt)} <= clock_timestamp()
        ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
        LIMIT $4
    )
    UNION ALL
    (
        SELECT ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)}
        FROM ${q(s.table)}
        WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = $1 AND ${q(s.state)} = 'processing'
          AND ${q(s.leaseExpiresAt)} <= clock_timestamp()
        ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
        LIMIT $4
    )
),
ready AS (
    SELECT ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)},
           row_number() OVER (
               PARTITION BY COALESCE(${q(s.aggregateId)}, '#' || ${q(s.id)}::text)
               ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT r.${q(s.id)}, r.${q(s.aggregateId)}, r.${q(s.scheduledAt)}, r.${q(s.createdAt)}
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.${q(s.aggregateId)} IS NULL OR NOT EXISTS (${busy("r")}))
    ORDER BY r.${q(s.scheduledAt)}, r.${q(s.createdAt)}, r.${q(s.id)}
    LIMIT $2
),
locked AS (
    SELECT i.${q(s.id)} FROM ${q(s.table)} AS i
    JOIN eligible AS e ON i.${q(s.id)} = e.${q(s.id)}
    WHERE i.${q(s.consumption)} = 'pull' AND i.${q(s.source)} = $1
      AND ((i.${q(s.state)} = 'pending' AND i.${q(s.scheduledAt)} <= clock_timestamp())
        OR (i.${q(s.state)} = 'processing' AND i.${q(s.leaseExpiresAt)} <= clock_timestamp()))
      AND (i.${q(s.aggregateId)} IS NULL OR NOT EXISTS (${busy("i")}))
    FOR UPDATE SKIP LOCKED
)
UPDATE ${q(s.table)} AS target
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = gen_random_uuid(), ${q(s.claimedAt)} = clock_timestamp(),
    ${q(s.leaseExpiresAt)} = clock_timestamp() + $3 * INTERVAL '1 millisecond'
FROM locked
WHERE target.${q(s.id)} = locked.${q(s.id)}
  AND target.${q(s.consumption)} = 'pull' AND target.${q(s.source)} = $1
  AND ((target.${q(s.state)} = 'pending' AND target.${q(s.scheduledAt)} <= clock_timestamp())
    OR (target.${q(s.state)} = 'processing' AND target.${q(s.leaseExpiresAt)} <= clock_timestamp()))
  AND (target.${q(s.aggregateId)} IS NULL OR NOT EXISTS (${busy("target")}))
RETURNING target.*`,
      params: ["source", "batch", "leaseMs", "candLimit"],
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

  // The claim takes at most one message per aggregate, exactly as the PostgreSQL claim does.
  // sp_getapplock serializes claims per source, scoped to @src, so different sources do not
  // block each other. It returns a negative value on a lock timeout or on a deadlock, and that
  // return is checked and THROWn below rather than left to proceed unserialized: a caller must
  // treat Msg 51000 as transient and back off before it retries the whole call. The local
  // variable names (@qbBatch, @qbLeaseMs, @qbCandLimit) are the canonical names of
  // examples/pull/sql/sqlserver/claim.sql, and they differ from the bound parameter names
  // (@p1..@p4) on purpose: a DECLARE cannot reuse a bound parameter name in the same batch, or
  // the claim fails on every call with Msg 134. @p1 is source, @p2 is batch, @p3 is leaseMs,
  // @p4 is candLimit.
  //
  // The statement carries its own transaction control (BEGIN TRANSACTION and COMMIT
  // TRANSACTION) and it must own its transaction scope: a caller must run it alone, never nested
  // inside a wider transaction. Nesting it stays uncommitted until the caller's own commit, lets
  // a lock failure destroy the caller's whole transaction, and holds the per-source applock open
  // for as long as the caller's own transaction stays open.
  const busy = (alias: string): string => `
            SELECT 1 FROM ${q(s.table)} AS busy
            WHERE busy.${q(s.aggregateId)} = ${alias}.${q(s.aggregateId)}
              AND busy.${q(s.source)} = @src
              AND busy.${q(s.consumption)} = 'pull'
              AND busy.${q(s.state)} = 'processing'
              AND busy.${q(s.leaseExpiresAt)} > SYSUTCDATETIME()`;

  return {
    claim: {
      text: `
BEGIN TRANSACTION;
DECLARE @qbBatch INT = @p2;
DECLARE @qbLeaseMs INT = @p3;
DECLARE @qbCandLimit INT = @p4; -- LEAST(GREATEST(3 * @qbBatch, 50), 500)
DECLARE @src VARCHAR(255) = @p1;
DECLARE @lockresult INT;
EXEC @lockresult = sp_getapplock @Resource = @src, @LockMode = 'Exclusive',
    @LockOwner = 'Transaction', @LockTimeout = 10000;
IF @lockresult < 0
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51000, 'inbox pull claim: sp_getapplock did not acquire the per-source claim lock', 1;
END
;WITH candidates AS (
    SELECT TOP (@qbCandLimit) ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)}
    FROM ${q(s.table)} WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = @src AND ${q(s.state)} = 'pending'
      AND ${q(s.scheduledAt)} <= SYSUTCDATETIME()
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
    UNION ALL
    SELECT TOP (@qbCandLimit) ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)}
    FROM ${q(s.table)} WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = @src AND ${q(s.state)} = 'processing'
      AND ${q(s.leaseExpiresAt)} <= SYSUTCDATETIME()
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
),
ready AS (
    SELECT ${q(s.id)}, ${q(s.aggregateId)}, ${q(s.scheduledAt)}, ${q(s.createdAt)},
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(${q(s.aggregateId)}, '#' + CAST(${q(s.id)} AS VARCHAR(36)))
               ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}, ${q(s.id)}
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT TOP (@qbBatch) r.${q(s.id)}, r.${q(s.aggregateId)}, r.${q(s.scheduledAt)}, r.${q(s.createdAt)}
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.${q(s.aggregateId)} IS NULL OR NOT EXISTS (${busy("r")}))
    ORDER BY r.${q(s.scheduledAt)}, r.${q(s.createdAt)}, r.${q(s.id)}
)
UPDATE target
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = NEWID(), ${q(s.claimedAt)} = SYSUTCDATETIME(),
    ${q(s.leaseExpiresAt)} = DATEADD(millisecond, @qbLeaseMs, SYSUTCDATETIME())
OUTPUT inserted.*
FROM ${q(s.table)} AS target WITH (UPDLOCK, ROWLOCK)
JOIN eligible AS e ON target.${q(s.id)} = e.${q(s.id)}
WHERE target.${q(s.consumption)} = 'pull' AND target.${q(s.source)} = @src
  AND ((target.${q(s.state)} = 'pending' AND target.${q(s.scheduledAt)} <= SYSUTCDATETIME())
    OR (target.${q(s.state)} = 'processing' AND target.${q(s.leaseExpiresAt)} <= SYSUTCDATETIME()))
  AND (target.${q(s.aggregateId)} IS NULL OR NOT EXISTS (${busy("target")}))
COMMIT TRANSACTION;`,
      params: ["source", "batch", "leaseMs", "candLimit"],
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
