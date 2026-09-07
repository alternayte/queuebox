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
	// Claim takes rows. Parameters: source, batch, leaseMS.
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

	return Statements{
		Claim: fmt.Sprintf(`
WITH candidates AS (
    SELECT %[1]s FROM %[2]s
    WHERE %[3]s = 'pull' AND %[4]s = $1
      AND ((%[5]s = 'pending' AND %[6]s <= clock_timestamp())
        OR (%[5]s = 'processing' AND %[7]s <= clock_timestamp()))
    ORDER BY %[6]s, %[8]s
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
UPDATE %[2]s AS target
SET %[5]s = 'processing', %[9]s = gen_random_uuid(),
    %[10]s = clock_timestamp(),
    %[7]s = clock_timestamp() + $3 * INTERVAL '1 millisecond'
FROM candidates WHERE target.%[1]s = candidates.%[1]s
RETURNING target.*`,
			q(s.ID), q(s.Table), q(s.Consumption), q(s.Source), q(s.State), q(s.ScheduledAt),
			q(s.LeaseExpiresAt), q(s.CreatedAt), q(s.ClaimToken), q(s.ClaimedAt)),

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

	return Statements{
		Claim: fmt.Sprintf(`
WITH candidates AS (
    SELECT TOP (@p2) * FROM %[1]s WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE %[2]s = 'pull' AND %[3]s = @p1
      AND ((%[4]s = 'pending' AND %[5]s <= SYSUTCDATETIME())
        OR (%[4]s = 'processing' AND %[6]s <= SYSUTCDATETIME()))
    ORDER BY %[5]s, %[7]s
)
UPDATE candidates
SET %[4]s = 'processing', %[8]s = NEWID(), %[9]s = SYSUTCDATETIME(),
    %[6]s = DATEADD(millisecond, @p3, SYSUTCDATETIME())
OUTPUT INSERTED.*`,
			q(s.Table), q(s.Consumption), q(s.Source), q(s.State), q(s.ScheduledAt),
			q(s.LeaseExpiresAt), q(s.CreatedAt), q(s.ClaimToken), q(s.ClaimedAt)),

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
