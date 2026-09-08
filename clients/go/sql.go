package queuebox

import (
	"fmt"
	"reflect"
	"regexp"
)

// Dialect is the database dialect that the inbox table lives in.
type Dialect string

const (
	// DialectPostgreSQL selects the PostgreSQL statements.
	DialectPostgreSQL Dialect = "postgresql"
	// DialectSQLServer selects the SQL Server statements.
	DialectSQLServer Dialect = "sqlserver"
)

// Schema names the inbox table and every column the library reads or writes.
// QueueBox lets an operator map these names, so the library must not assume the defaults.
type Schema struct {
	Table          string
	ID             string
	Consumption    string
	Source         string
	State          string
	ScheduledAt    string
	CreatedAt      string
	ClaimToken     string
	ClaimedAt      string
	LeaseExpiresAt string
	ProcessedAt    string
	Attempt        string
	LastError      string
	IdempotencyKey string
	AggregateID    string
	EventType      string
	Payload        string
	CorrelationID  string
}

// DefaultSchema returns the QueueBox V6 names.
func DefaultSchema() Schema {
	return Schema{
		Table:          "inbox",
		ID:             "id",
		Consumption:    "consumption",
		Source:         "source",
		State:          "state",
		ScheduledAt:    "scheduled_at",
		CreatedAt:      "created_at",
		ClaimToken:     "claim_token",
		ClaimedAt:      "claimed_at",
		LeaseExpiresAt: "lease_expires_at",
		ProcessedAt:    "processed_at",
		Attempt:        "attempt",
		LastError:      "last_error",
		IdempotencyKey: "idempotency_key",
		AggregateID:    "aggregate_id",
		EventType:      "event_type",
		Payload:        "payload",
		CorrelationID:  "correlation_id",
	}
}

// Statements holds the five statements of the pull contract, rendered for one dialect and one
// schema.
//
// Both dialects bind POSITIONALLY, because the PostgreSQL driver accepts no named parameter.
// PostgreSQL writes $1 and SQL Server writes @p1, so one database/sql call site serves both.
type Statements struct {
	// Claim takes rows. Parameters: source, batch, leaseMS, candLimit.
	//
	// The SQL Server text carries its own transaction control (BEGIN TRANSACTION and COMMIT
	// TRANSACTION) and it must own its transaction scope: run it alone, never nested inside a
	// wider transaction. A wider transaction changes three things silently: the claimed rows
	// stay uncommitted until the caller's own commit, a lock failure rolls back the caller's
	// whole transaction instead of only the claim, and the per-source applock stays held for as
	// long as the caller's transaction stays open instead of releasing at the claim's own
	// commit.
	Claim string
	// Renew extends the lease. Parameters: leaseMS, id, token.
	Renew string
	// Complete marks the row processed. Parameters: id, token.
	Complete string
	// Retry returns the row to pending. Parameters: delayMS, error, id, token.
	Retry string
	// Dead marks the row dead. Parameters: error, id, token.
	Dead string
}

// An identifier is quoted before it reaches the database, and it is also checked here.
var safeIdentifier = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_$]{0,62}$`)

func (s Schema) validate() error {
	value := reflect.ValueOf(s)
	fields := value.Type()

	for i := range value.NumField() {
		name := value.Field(i).String()

		if !safeIdentifier.MatchString(name) {
			return fmt.Errorf("the schema name %s is not a plain SQL identifier", fields.Field(i).Name)
		}
	}

	return nil
}

// NewStatements renders the five statements of the pull contract.
//
// The library uses it, and an application that wants to run one statement by hand can use it too.
// Every identifier is quoted and checked, so a schema mapping cannot carry SQL.
//
// A caller who runs the SQL Server Claim by hand must run it alone, never nested inside a wider
// transaction: see the Claim field for the three consequences of nesting it.
func NewStatements(dialect Dialect, schema Schema) (Statements, error) {
	if err := schema.validate(); err != nil {
		return Statements{}, err
	}

	switch dialect {
	case DialectPostgreSQL:
		return postgresqlStatements(schema), nil
	case DialectSQLServer:
		return sqlserverStatements(schema), nil
	default:
		return Statements{}, fmt.Errorf("the dialect %q is not supported", dialect)
	}
}

func postgresqlStatements(s Schema) Statements {
	q := func(name string) string { return `"` + name + `"` }
	fence := func(id, token string) string {
		return fmt.Sprintf("\nWHERE %s = %s AND %s = 'pull' AND %s = 'processing'\n  AND %s = %s AND %s > clock_timestamp()",
			q(s.ID), id, q(s.Consumption), q(s.State), q(s.ClaimToken), token, q(s.LeaseExpiresAt))
	}

	// The claim takes at most one message per aggregate. A row whose aggregate already holds a
	// message in state 'processing' under a live lease is not a candidate, and two rows of one
	// aggregate never leave one claim together. The full readiness predicate repeats in the
	// locking read and again in the final UPDATE, which restores the EvalPlanQual re-check:
	// without it, two workers can claim the same row. The text follows
	// examples/pull/sql/postgresql/claim.sql exactly; only the identifiers and the parameter
	// placeholders move. $1 is source, $2 is batch, $3 is leaseMS, $4 is candLimit.
	claim := fmt.Sprintf(`
WITH candidates AS (
    (
        SELECT %[1]s, %[9]s, %[6]s, %[8]s
        FROM %[2]s
        WHERE %[3]s = 'pull' AND %[4]s = $1 AND %[5]s = 'pending'
          AND %[6]s <= clock_timestamp()
        ORDER BY %[6]s, %[8]s, %[1]s
        LIMIT $4
    )
    UNION ALL
    (
        SELECT %[1]s, %[9]s, %[6]s, %[8]s
        FROM %[2]s
        WHERE %[3]s = 'pull' AND %[4]s = $1 AND %[5]s = 'processing'
          AND %[7]s <= clock_timestamp()
        ORDER BY %[6]s, %[8]s, %[1]s
        LIMIT $4
    )
),
ready AS (
    SELECT %[1]s, %[9]s, %[6]s, %[8]s,
           row_number() OVER (
               PARTITION BY COALESCE(%[9]s, '#' || %[1]s::text)
               ORDER BY %[6]s, %[8]s, %[1]s
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT r.%[1]s, r.%[9]s, r.%[6]s, r.%[8]s
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.%[9]s IS NULL OR NOT EXISTS (
            SELECT 1 FROM %[2]s AS busy
            WHERE busy.%[9]s = r.%[9]s
              AND busy.%[4]s = $1
              AND busy.%[3]s = 'pull'
              AND busy.%[5]s = 'processing'
              AND busy.%[7]s > clock_timestamp()))
    ORDER BY r.%[6]s, r.%[8]s, r.%[1]s
    LIMIT $2
),
locked AS (
    SELECT i.%[1]s FROM %[2]s AS i
    JOIN eligible AS e ON i.%[1]s = e.%[1]s
    WHERE i.%[3]s = 'pull' AND i.%[4]s = $1
      AND ((i.%[5]s = 'pending' AND i.%[6]s <= clock_timestamp())
        OR (i.%[5]s = 'processing' AND i.%[7]s <= clock_timestamp()))
      AND (i.%[9]s IS NULL OR NOT EXISTS (
            SELECT 1 FROM %[2]s AS busy
            WHERE busy.%[9]s = i.%[9]s
              AND busy.%[4]s = $1
              AND busy.%[3]s = 'pull'
              AND busy.%[5]s = 'processing'
              AND busy.%[7]s > clock_timestamp()))
    FOR UPDATE SKIP LOCKED
)
UPDATE %[2]s AS target
SET %[5]s = 'processing', %[10]s = gen_random_uuid(), %[11]s = clock_timestamp(),
    %[7]s = clock_timestamp() + $3 * INTERVAL '1 millisecond'
FROM locked
WHERE target.%[1]s = locked.%[1]s
  AND target.%[3]s = 'pull' AND target.%[4]s = $1
  AND ((target.%[5]s = 'pending' AND target.%[6]s <= clock_timestamp())
    OR (target.%[5]s = 'processing' AND target.%[7]s <= clock_timestamp()))
  AND (target.%[9]s IS NULL OR NOT EXISTS (
        SELECT 1 FROM %[2]s AS busy
        WHERE busy.%[9]s = target.%[9]s
          AND busy.%[4]s = $1
          AND busy.%[3]s = 'pull'
          AND busy.%[5]s = 'processing'
          AND busy.%[7]s > clock_timestamp()))
RETURNING target.*`,
		q(s.ID), q(s.Table), q(s.Consumption), q(s.Source), q(s.State), q(s.ScheduledAt),
		q(s.LeaseExpiresAt), q(s.CreatedAt), q(s.AggregateID), q(s.ClaimToken), q(s.ClaimedAt))

	return Statements{
		Claim: claim,

		Renew: fmt.Sprintf("UPDATE %s SET %s = clock_timestamp() + $1 * INTERVAL '1 millisecond'%s",
			q(s.Table), q(s.LeaseExpiresAt), fence("$2", "$3")),

		Complete: fmt.Sprintf("UPDATE %s SET %s = 'processed', %s = clock_timestamp(), %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.ProcessedAt), q(s.ClaimToken), q(s.LeaseExpiresAt), fence("$1", "$2")),

		Retry: fmt.Sprintf("UPDATE %s SET %s = 'pending', %s = clock_timestamp() + $1 * INTERVAL '1 millisecond', %s = %s + 1, %s = $2, %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.ScheduledAt), q(s.Attempt), q(s.Attempt), q(s.LastError),
			q(s.ClaimToken), q(s.LeaseExpiresAt), fence("$3", "$4")),

		Dead: fmt.Sprintf("UPDATE %s SET %s = 'dead', %s = $1, %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.LastError), q(s.ClaimToken), q(s.LeaseExpiresAt), fence("$2", "$3")),
	}
}

func sqlserverStatements(s Schema) Statements {
	q := func(name string) string { return "[" + name + "]" }
	fence := func(id, token string) string {
		return fmt.Sprintf("\nWHERE %s = %s AND %s = 'pull' AND %s = 'processing'\n  AND %s = %s AND %s > SYSUTCDATETIME()",
			q(s.ID), id, q(s.Consumption), q(s.State), q(s.ClaimToken), token, q(s.LeaseExpiresAt))
	}

	// The claim takes at most one message per aggregate, exactly as the PostgreSQL claim does.
	// sp_getapplock serializes claims per source, scoped to @src so different sources do not
	// block each other. It returns a negative value on a lock timeout or on a deadlock, and that
	// return is checked and THROWn below rather than left to proceed unserialized: a caller must
	// treat Msg 51000 as transient and back off before it retries the whole call. The local
	// variable names (@qbBatch, @qbLeaseMs, @qbCandLimit) are the canonical names of
	// examples/pull/sql/sqlserver/claim.sql, and they differ from the bound parameter names
	// (@p1..@p4) on purpose: a DECLARE cannot reuse a bound parameter name in the same batch, or
	// the claim fails on every call with Msg 134. @p1 is source, @p2 is batch, @p3 is leaseMS,
	// @p4 is candLimit.
	claim := fmt.Sprintf(`
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
    SELECT TOP (@qbCandLimit) %[1]s, %[9]s, %[6]s, %[8]s
    FROM %[2]s WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE %[3]s = 'pull' AND %[4]s = @src AND %[5]s = 'pending'
      AND %[6]s <= SYSUTCDATETIME()
    ORDER BY %[6]s, %[8]s, %[1]s
    UNION ALL
    SELECT TOP (@qbCandLimit) %[1]s, %[9]s, %[6]s, %[8]s
    FROM %[2]s WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE %[3]s = 'pull' AND %[4]s = @src AND %[5]s = 'processing'
      AND %[7]s <= SYSUTCDATETIME()
    ORDER BY %[6]s, %[8]s, %[1]s
),
ready AS (
    SELECT %[1]s, %[9]s, %[6]s, %[8]s,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(%[9]s, '#' + CAST(%[1]s AS VARCHAR(36)))
               ORDER BY %[6]s, %[8]s, %[1]s
           ) AS rn
    FROM candidates
),
eligible AS (
    SELECT TOP (@qbBatch) r.%[1]s, r.%[9]s, r.%[6]s, r.%[8]s
    FROM ready AS r
    WHERE r.rn = 1
      AND (r.%[9]s IS NULL OR NOT EXISTS (
            SELECT 1 FROM %[2]s AS busy
            WHERE busy.%[9]s = r.%[9]s
              AND busy.%[4]s = @src
              AND busy.%[3]s = 'pull'
              AND busy.%[5]s = 'processing'
              AND busy.%[7]s > SYSUTCDATETIME()))
    ORDER BY r.%[6]s, r.%[8]s, r.%[1]s
)
UPDATE target
SET %[5]s = 'processing', %[10]s = NEWID(), %[11]s = SYSUTCDATETIME(),
    %[7]s = DATEADD(millisecond, @qbLeaseMs, SYSUTCDATETIME())
OUTPUT inserted.*
FROM %[2]s AS target WITH (UPDLOCK, ROWLOCK)
JOIN eligible AS e ON target.%[1]s = e.%[1]s
WHERE target.%[3]s = 'pull' AND target.%[4]s = @src
  AND ((target.%[5]s = 'pending' AND target.%[6]s <= SYSUTCDATETIME())
    OR (target.%[5]s = 'processing' AND target.%[7]s <= SYSUTCDATETIME()))
  AND (target.%[9]s IS NULL OR NOT EXISTS (
        SELECT 1 FROM %[2]s AS busy
        WHERE busy.%[9]s = target.%[9]s
          AND busy.%[4]s = @src
          AND busy.%[3]s = 'pull'
          AND busy.%[5]s = 'processing'
          AND busy.%[7]s > SYSUTCDATETIME()));
COMMIT TRANSACTION;`,
		q(s.ID), q(s.Table), q(s.Consumption), q(s.Source), q(s.State), q(s.ScheduledAt),
		q(s.LeaseExpiresAt), q(s.CreatedAt), q(s.AggregateID), q(s.ClaimToken), q(s.ClaimedAt))

	return Statements{
		Claim: claim,

		Renew: fmt.Sprintf("UPDATE %s SET %s = DATEADD(millisecond, @p1, SYSUTCDATETIME())%s",
			q(s.Table), q(s.LeaseExpiresAt), fence("@p2", "@p3")),

		Complete: fmt.Sprintf("UPDATE %s SET %s = 'processed', %s = SYSUTCDATETIME(), %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.ProcessedAt), q(s.ClaimToken), q(s.LeaseExpiresAt), fence("@p1", "@p2")),

		Retry: fmt.Sprintf("UPDATE %s SET %s = 'pending', %s = DATEADD(millisecond, @p1, SYSUTCDATETIME()), %s = %s + 1, %s = @p2, %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.ScheduledAt), q(s.Attempt), q(s.Attempt), q(s.LastError),
			q(s.ClaimToken), q(s.LeaseExpiresAt), fence("@p3", "@p4")),

		Dead: fmt.Sprintf("UPDATE %s SET %s = 'dead', %s = @p1, %s = NULL, %s = NULL%s",
			q(s.Table), q(s.State), q(s.LastError), q(s.ClaimToken), q(s.LeaseExpiresAt), fence("@p2", "@p3")),
	}
}
