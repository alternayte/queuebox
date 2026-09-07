package contract_test

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	mssqldb "github.com/microsoft/go-mssqldb"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/mssql"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	queuebox "github.com/alternayte/queuebox/clients/go"
)

// harness is one database under test. The contract tests are written once and run against every
// harness, because a difference between two dialects must never be a difference of guarantee.
type harness interface {
	dialect() queuebox.Dialect
	db() *sql.DB
	// dsn returns the connection string, so a test can open a second pool of its own.
	dsn() string
	start(t *testing.T)
	reset(t *testing.T)
	insertApplicationRow() string
	insertPending(t *testing.T, row pendingRow) string
	readRow(t *testing.T, id string) (state string, attempt int, lastError *string)
	readScheduledAt(t *testing.T, id string) time.Time
	stealClaim(t *testing.T, id string)
	countApplicationRows(t *testing.T, key string) int
	createMappedSchema(t *testing.T) queuebox.Schema
	insertMappedPending(t *testing.T, schema queuebox.Schema, source, key, payload string) string
	readMappedState(t *testing.T, schema queuebox.Schema, id string) string
}

type pendingRow struct {
	source         string
	idempotencyKey string
	payload        string
	aggregateID    *string
	eventType      *string
	correlationID  *string
	attempt        int
}

// migrations reads the QueueBox migrations from the repository. The tests apply the real schema,
// so the library cannot drift away from it.
func migrations(t *testing.T, directory string) []string {
	t.Helper()

	module := "postgres"
	if directory == "sqlserver" {
		module = "sqlserver"
	}

	path := filepath.Join(repositoryRoot(t), module, "src", "main", "resources", "db", directory)

	entries, err := os.ReadDir(path)
	if err != nil {
		t.Fatalf("the migrations did not list: %v", err)
	}

	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".sql" {
			names = append(names, entry.Name())
		}
	}

	version := regexp.MustCompile(`^V(\d+)__`)
	sort.Slice(names, func(i, j int) bool {
		return versionOf(version, names[i]) < versionOf(version, names[j])
	})

	scripts := make([]string, 0, len(names))
	for _, name := range names {
		content, err := os.ReadFile(filepath.Join(path, name))
		if err != nil {
			t.Fatalf("the migration %s did not read: %v", name, err)
		}

		scripts = append(scripts, string(content))
	}

	return scripts
}

func versionOf(pattern *regexp.Regexp, name string) int {
	match := pattern.FindStringSubmatch(name)
	if match == nil {
		return 0
	}

	number, _ := strconv.Atoi(match[1])

	return number
}

func repositoryRoot(t *testing.T) string {
	t.Helper()

	directory, err := os.Getwd()
	if err != nil {
		t.Fatalf("the working directory is unknown: %v", err)
	}

	for {
		if _, err := os.Stat(filepath.Join(directory, ".git")); err == nil {
			return directory
		}

		parent := filepath.Dir(directory)
		if parent == directory {
			t.Fatal("the repository root was not found")
		}

		directory = parent
	}
}

// mappedSchema names the mapped columns that item 13 uses.
func mappedSchema() queuebox.Schema {
	schema := queuebox.DefaultSchema()
	schema.Table = "qb_messages"
	schema.ID = "message_id"
	schema.State = "row_state"
	schema.Source = "channel"
	schema.Payload = "body"
	schema.IdempotencyKey = "dedup_key"
	schema.ClaimToken = "lease_token"
	schema.Attempt = "tries"

	return schema
}

// ---- PostgreSQL ----

type postgresHarness struct {
	pool *sql.DB
	uri  string
}

func (h *postgresHarness) dsn() string { return h.uri }

func (h *postgresHarness) dialect() queuebox.Dialect { return queuebox.DialectPostgreSQL }
func (h *postgresHarness) db() *sql.DB               { return h.pool }
func (h *postgresHarness) insertApplicationRow() string {
	return "INSERT INTO app_orders (key) VALUES ($1)"
}

func (h *postgresHarness) start(t *testing.T) {
	ctx := context.Background()

	container, err := postgres.Run(ctx, "postgres:16-alpine",
		postgres.WithDatabase("queuebox"),
		postgres.WithUsername("queuebox"),
		postgres.WithPassword("queuebox"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(2*time.Minute)),
	)
	if err != nil {
		t.Fatalf("the container did not start: %v", err)
	}

	t.Cleanup(func() { _ = container.Terminate(context.Background()) })

	uri, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatalf("the connection string is unknown: %v", err)
	}

	h.uri = uri

	pool, err := sql.Open("pgx", uri)
	if err != nil {
		t.Fatalf("the pool did not open: %v", err)
	}

	t.Cleanup(func() { _ = pool.Close() })
	h.pool = pool

	for _, script := range migrations(t, "postgresql") {
		mustExec(t, pool, script)
	}

	mustExec(t, pool, "CREATE TABLE app_orders (key TEXT PRIMARY KEY)")
}

func (h *postgresHarness) reset(t *testing.T) {
	mustExec(t, h.pool, "DELETE FROM inbox")
	mustExec(t, h.pool, "DELETE FROM app_orders")
}

func (h *postgresHarness) insertPending(t *testing.T, row pendingRow) string {
	var id string

	err := h.pool.QueryRow(`
		INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
		                   consumption, scheduled_at, attempt, correlation_id)
		VALUES ($1, $2, $3, $4, $5::jsonb, 'pending', 'pull', CURRENT_TIMESTAMP, $6, $7)
		RETURNING id`,
		row.source, row.idempotencyKey, row.aggregateID, row.eventType, row.payload, row.attempt, row.correlationID,
	).Scan(&id)
	if err != nil {
		t.Fatalf("the row did not store: %v", err)
	}

	return id
}

func (h *postgresHarness) readRow(t *testing.T, id string) (string, int, *string) {
	var (
		state     string
		attempt   int
		lastError *string
	)

	if err := h.pool.QueryRow("SELECT state, attempt, last_error FROM inbox WHERE id = $1", id).
		Scan(&state, &attempt, &lastError); err != nil {
		t.Fatalf("the row did not read: %v", err)
	}

	return state, attempt, lastError
}

func (h *postgresHarness) readScheduledAt(t *testing.T, id string) time.Time {
	var at time.Time

	if err := h.pool.QueryRow("SELECT scheduled_at FROM inbox WHERE id = $1", id).Scan(&at); err != nil {
		t.Fatalf("the schedule did not read: %v", err)
	}

	return at
}

func (h *postgresHarness) stealClaim(t *testing.T, id string) {
	// A real second worker also takes the row into 'processing'.
	mustExecArgs(t, h.pool, `
		UPDATE inbox SET state = 'processing', claim_token = gen_random_uuid(),
		                 lease_expires_at = clock_timestamp() + INTERVAL '10 minutes'
		WHERE id = $1`, id)
}

func (h *postgresHarness) countApplicationRows(t *testing.T, key string) int {
	var count int

	if err := h.pool.QueryRow("SELECT COUNT(*) FROM app_orders WHERE key = $1", key).Scan(&count); err != nil {
		t.Fatalf("the count did not read: %v", err)
	}

	return count
}

func (h *postgresHarness) createMappedSchema(t *testing.T) queuebox.Schema {
	mustExec(t, h.pool, "DROP TABLE IF EXISTS qb_messages")
	mustExec(t, h.pool, `
		CREATE TABLE qb_messages (
			message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			channel VARCHAR(255) NOT NULL,
			dedup_key VARCHAR(255) NOT NULL,
			aggregate_id VARCHAR(255),
			event_type VARCHAR(255),
			body JSONB NOT NULL,
			row_state VARCHAR(50) NOT NULL DEFAULT 'pending',
			created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
			processed_at TIMESTAMPTZ,
			correlation_id VARCHAR(128),
			lease_token UUID,
			claimed_at TIMESTAMPTZ,
			lease_expires_at TIMESTAMPTZ,
			consumption VARCHAR(4) NOT NULL DEFAULT 'push',
			scheduled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
			tries INT NOT NULL DEFAULT 0,
			last_error TEXT
		)`)

	return mappedSchema()
}

func (h *postgresHarness) insertMappedPending(t *testing.T, schema queuebox.Schema, source, key, payload string) string {
	var id string

	query := "INSERT INTO " + schema.Table + " (" + schema.Source + ", " + schema.IdempotencyKey + ", " +
		schema.Payload + ", " + schema.State + ", " + schema.Consumption +
		") VALUES ($1, $2, $3::jsonb, 'pending', 'pull') RETURNING " + schema.ID

	if err := h.pool.QueryRow(query, source, key, payload).Scan(&id); err != nil {
		t.Fatalf("the mapped row did not store: %v", err)
	}

	return id
}

func (h *postgresHarness) readMappedState(t *testing.T, schema queuebox.Schema, id string) string {
	var state string

	query := "SELECT " + schema.State + " FROM " + schema.Table + " WHERE " + schema.ID + " = $1"
	if err := h.pool.QueryRow(query, id).Scan(&state); err != nil {
		t.Fatalf("the mapped state did not read: %v", err)
	}

	return state
}

// ---- SQL Server ----

type sqlServerHarness struct {
	pool *sql.DB
	uri  string
}

func (h *sqlServerHarness) dsn() string { return h.uri }

func (h *sqlServerHarness) dialect() queuebox.Dialect { return queuebox.DialectSQLServer }
func (h *sqlServerHarness) db() *sql.DB               { return h.pool }
func (h *sqlServerHarness) insertApplicationRow() string {
	return "INSERT INTO app_orders ([key]) VALUES (@p1)"
}

func (h *sqlServerHarness) start(t *testing.T) {
	ctx := context.Background()

	container, err := mssql.Run(ctx, "mcr.microsoft.com/mssql/server:2022-latest", mssql.WithAcceptEULA())
	if err != nil {
		t.Fatalf("the container did not start: %v", err)
	}

	t.Cleanup(func() { _ = container.Terminate(context.Background()) })

	uri, err := container.ConnectionString(ctx)
	if err != nil {
		t.Fatalf("the connection string is unknown: %v", err)
	}

	h.uri = uri

	pool, err := sql.Open("sqlserver", uri)
	if err != nil {
		t.Fatalf("the pool did not open: %v", err)
	}

	t.Cleanup(func() { _ = pool.Close() })
	h.pool = pool

	for _, script := range migrations(t, "sqlserver") {
		mustExec(t, pool, script)
	}

	mustExec(t, pool, "CREATE TABLE app_orders ([key] NVARCHAR(255) PRIMARY KEY)")
}

func (h *sqlServerHarness) reset(t *testing.T) {
	mustExec(t, h.pool, "DELETE FROM inbox")
	mustExec(t, h.pool, "DELETE FROM app_orders")
}

func (h *sqlServerHarness) insertPending(t *testing.T, row pendingRow) string {
	// The driver returns a UNIQUEIDENTIFIER as raw bytes, so the test reads it through the
	// driver's own type and hands canonical text to the library.
	var id mssqldb.UniqueIdentifier

	err := h.pool.QueryRow(`
		INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
		                   consumption, scheduled_at, attempt, correlation_id)
		OUTPUT INSERTED.id
		VALUES (@p1, @p2, @p3, @p4, @p5, 'pending', 'pull', SYSUTCDATETIME(), @p6, @p7)`,
		row.source, row.idempotencyKey, row.aggregateID, row.eventType, row.payload, row.attempt, row.correlationID,
	).Scan(&id)
	if err != nil {
		t.Fatalf("the row did not store: %v", err)
	}

	return id.String()
}

func (h *sqlServerHarness) readRow(t *testing.T, id string) (string, int, *string) {
	var (
		state     string
		attempt   int
		lastError *string
	)

	if err := h.pool.QueryRow("SELECT state, attempt, last_error FROM inbox WHERE id = @p1", id).
		Scan(&state, &attempt, &lastError); err != nil {
		t.Fatalf("the row did not read: %v", err)
	}

	return state, attempt, lastError
}

func (h *sqlServerHarness) readScheduledAt(t *testing.T, id string) time.Time {
	var at time.Time

	if err := h.pool.QueryRow("SELECT scheduled_at FROM inbox WHERE id = @p1", id).Scan(&at); err != nil {
		t.Fatalf("the schedule did not read: %v", err)
	}

	return at
}

func (h *sqlServerHarness) stealClaim(t *testing.T, id string) {
	mustExecArgs(t, h.pool, `
		UPDATE inbox SET state = 'processing', claim_token = NEWID(),
		                 lease_expires_at = DATEADD(minute, 10, SYSUTCDATETIME())
		WHERE id = @p1`, id)
}

func (h *sqlServerHarness) countApplicationRows(t *testing.T, key string) int {
	var count int

	if err := h.pool.QueryRow("SELECT COUNT(*) FROM app_orders WHERE [key] = @p1", key).Scan(&count); err != nil {
		t.Fatalf("the count did not read: %v", err)
	}

	return count
}

func (h *sqlServerHarness) createMappedSchema(t *testing.T) queuebox.Schema {
	mustExec(t, h.pool, "IF OBJECT_ID('qb_messages', 'U') IS NOT NULL DROP TABLE qb_messages")
	mustExec(t, h.pool, `
		CREATE TABLE qb_messages (
			message_id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
			channel NVARCHAR(255) NOT NULL,
			dedup_key NVARCHAR(255) NOT NULL,
			aggregate_id NVARCHAR(255),
			event_type NVARCHAR(255),
			body NVARCHAR(MAX) NOT NULL,
			row_state NVARCHAR(50) NOT NULL DEFAULT 'pending',
			created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
			processed_at DATETIME2,
			correlation_id NVARCHAR(128),
			lease_token UNIQUEIDENTIFIER,
			claimed_at DATETIME2,
			lease_expires_at DATETIME2,
			consumption NVARCHAR(4) NOT NULL DEFAULT 'push',
			scheduled_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
			tries INT NOT NULL DEFAULT 0,
			last_error NVARCHAR(MAX)
		)`)

	return mappedSchema()
}

func (h *sqlServerHarness) insertMappedPending(t *testing.T, schema queuebox.Schema, source, key, payload string) string {
	var id mssqldb.UniqueIdentifier

	query := "INSERT INTO " + schema.Table + " (" + schema.Source + ", " + schema.IdempotencyKey + ", " +
		schema.Payload + ", " + schema.State + ", " + schema.Consumption +
		") OUTPUT INSERTED." + schema.ID + " VALUES (@p1, @p2, @p3, 'pending', 'pull')"

	if err := h.pool.QueryRow(query, source, key, payload).Scan(&id); err != nil {
		t.Fatalf("the mapped row did not store: %v", err)
	}

	return id.String()
}

func (h *sqlServerHarness) readMappedState(t *testing.T, schema queuebox.Schema, id string) string {
	var state string

	query := "SELECT " + schema.State + " FROM " + schema.Table + " WHERE " + schema.ID + " = @p1"
	if err := h.pool.QueryRow(query, id).Scan(&state); err != nil {
		t.Fatalf("the mapped state did not read: %v", err)
	}

	return state
}

func mustExec(t *testing.T, pool *sql.DB, statement string) {
	t.Helper()

	if _, err := pool.Exec(statement); err != nil {
		t.Fatalf("the statement failed: %v", err)
	}
}

func mustExecArgs(t *testing.T, pool *sql.DB, statement string, args ...any) {
	t.Helper()

	if _, err := pool.Exec(statement, args...); err != nil {
		t.Fatalf("the statement failed: %v", err)
	}
}

// mustStatements renders the contract statements for a dialect, or fails the test.
func mustStatements(t *testing.T, dialect queuebox.Dialect) queuebox.Statements {
	t.Helper()

	rendered, err := queuebox.NewStatements(dialect, queuebox.DefaultSchema())
	if err != nil {
		t.Fatalf("the statements did not render: %v", err)
	}

	return rendered
}

// countingDriver wraps the driver of a harness and counts the statements that run through it.
// The claim is the only statement that a worker runs against an empty table, so the count is the
// number of claims.
type countingDriver struct {
	inner   driver.Driver
	counter *atomic.Int64
}

func (d countingDriver) Open(name string) (driver.Conn, error) {
	conn, err := d.inner.Open(name)
	if err != nil {
		return nil, err
	}

	return countingConn{inner: conn, counter: d.counter}, nil
}

type countingConn struct {
	inner   driver.Conn
	counter *atomic.Int64
}

// Prepare counts as well as QueryContext, because the two drivers take different paths.
// database/sql calls QueryContext when the driver offers it, and prepares a statement when it
// does not, so exactly one of the two counts each claim.
func (c countingConn) Prepare(query string) (driver.Stmt, error) {
	c.counter.Add(1)

	return c.inner.Prepare(query)
}

func (c countingConn) PrepareContext(ctx context.Context, query string) (driver.Stmt, error) {
	c.counter.Add(1)

	preparer, ok := c.inner.(driver.ConnPrepareContext)
	if !ok {
		return c.inner.Prepare(query)
	}

	return preparer.PrepareContext(ctx, query)
}
func (c countingConn) Close() error              { return c.inner.Close() }
func (c countingConn) Begin() (driver.Tx, error) { return c.inner.Begin() } //nolint:staticcheck

func (c countingConn) BeginTx(ctx context.Context, options driver.TxOptions) (driver.Tx, error) {
	if beginner, ok := c.inner.(driver.ConnBeginTx); ok {
		return beginner.BeginTx(ctx, options)
	}

	return c.inner.Begin() //nolint:staticcheck
}

func (c countingConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	querier, ok := c.inner.(driver.QueryerContext)
	if !ok {
		return nil, driver.ErrSkip
	}

	c.counter.Add(1)

	return querier.QueryContext(ctx, query, args)
}

func (c countingConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	executor, ok := c.inner.(driver.ExecerContext)
	if !ok {
		return nil, driver.ErrSkip
	}

	return executor.ExecContext(ctx, query, args)
}

var countingDriverNames atomic.Int64

// countingPool opens a second pool whose queries are counted.
func countingPool(t *testing.T, h harness) (*sql.DB, *atomic.Int64) {
	t.Helper()

	counter := &atomic.Int64{}
	name := fmt.Sprintf("queuebox-counting-%d", countingDriverNames.Add(1))

	sql.Register(name, countingDriver{inner: h.db().Driver(), counter: counter})

	pool, err := sql.Open(name, h.dsn())
	if err != nil {
		t.Fatalf("the counting pool did not open: %v", err)
	}

	t.Cleanup(func() { _ = pool.Close() })

	return pool, counter
}
