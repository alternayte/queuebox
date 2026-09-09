package queuebox

import (
	"strings"
	"testing"
)

func TestPostgreSQLClaimMatchesTheContract(t *testing.T) {
	statements, err := NewStatements(DialectPostgreSQL, DefaultSchema())
	if err != nil {
		t.Fatalf("the statements did not render: %v", err)
	}

	for _, want := range []string{"FOR UPDATE SKIP LOCKED", `FROM "inbox"`, "$1", "$4"} {
		if !strings.Contains(statements.Claim, want) {
			t.Errorf("the claim does not contain %q", want)
		}
	}

	if strings.Contains(statements.Claim, ":source") {
		t.Error("the claim still carries a named parameter")
	}

	// The readiness check ('pending' and due, or 'processing' and lease expired) must repeat in
	// the locking read and again in the final UPDATE: that repetition restores the EvalPlanQual
	// re-check. Fewer occurrences than the candidate-branch check plus the locking read plus the
	// final UPDATE means one of the two later checks is missing, and two workers can then claim
	// the same row.
	if got := strings.Count(statements.Claim, "= 'pending'"); got != 3 {
		t.Errorf("the 'pending' readiness check appears %d times and it must appear 3 times", got)
	}

	// The busy check is scoped to (source, aggregate_id) at every occurrence: the eligible
	// filter, the locking read and the final UPDATE.
	if got := strings.Count(statements.Claim, `busy."source" = $1`); got != 3 {
		t.Errorf("the source-scoped busy check appears %d times and it must appear 3 times", got)
	}

	// The candidate selection is a UNION ALL of two bounded branches, ahead of the window
	// function that reserves one row per aggregate.
	if got := strings.Count(statements.Claim, "UNION ALL"); got != 1 {
		t.Errorf("the candidate selection has %d UNION ALL and it must have exactly 1", got)
	}

	// Every ORDER BY ends in the id column, so the order is total.
	for _, line := range strings.Split(statements.Claim, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "ORDER BY") && !strings.HasSuffix(trimmed, `"id"`) && !strings.HasSuffix(trimmed, `r."id"`) {
			t.Errorf("an ORDER BY does not end in the id column: %q", trimmed)
		}
	}
}

func TestSQLServerClaimMatchesTheContract(t *testing.T) {
	statements, err := NewStatements(DialectSQLServer, DefaultSchema())
	if err != nil {
		t.Fatalf("the statements did not render: %v", err)
	}

	for _, want := range []string{"UPDLOCK, READPAST, ROWLOCK", "TOP (@qbCandLimit)", "TOP (@qbBatch)", "OUTPUT inserted.*"} {
		if !strings.Contains(statements.Claim, want) {
			t.Errorf("the claim does not contain %q", want)
		}
	}

	// The local T-SQL variables must never share a name with a bound parameter: the driver
	// declares the parameter for the caller, and a matching DECLARE raises Msg 134.
	for _, local := range []string{"@qbBatch", "@qbLeaseMs", "@qbCandLimit", "@src"} {
		if !strings.Contains(statements.Claim, "DECLARE "+local) {
			t.Errorf("the claim does not declare the local variable %s", local)
		}
	}

	for _, bound := range []string{"@p1", "@p2", "@p3", "@p4"} {
		if !strings.Contains(statements.Claim, bound) {
			t.Errorf("the claim does not bind the parameter %s", bound)
		}
	}

	// sp_getapplock is taken once per source. The EXEC line names it, and the THROW message
	// names it again in the failure text, so it appears exactly twice.
	if got := strings.Count(statements.Claim, "sp_getapplock"); got != 2 {
		t.Errorf("sp_getapplock appears %d times and it must appear exactly twice", got)
	}

	if !strings.Contains(statements.Claim, "IF @lockresult < 0") || !strings.Contains(statements.Claim, "THROW 51000") {
		t.Error("the claim does not check the applock result and THROW Msg 51000 on a negative return")
	}

	// The readiness check repeats in the candidate branch and again in the final UPDATE.
	if got := strings.Count(statements.Claim, "= 'pending'"); got != 2 {
		t.Errorf("the 'pending' readiness check appears %d times and it must appear twice", got)
	}

	// The busy check is scoped to (source, aggregate_id) at every occurrence: the eligible
	// filter and the final UPDATE.
	if got := strings.Count(statements.Claim, "busy.[source] = @src"); got != 2 {
		t.Errorf("the source-scoped busy check appears %d times and it must appear 2 times", got)
	}

	if got := strings.Count(statements.Claim, "UNION ALL"); got != 1 {
		t.Errorf("the candidate selection has %d UNION ALL and it must have exactly 1", got)
	}

	for _, line := range strings.Split(statements.Claim, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "ORDER BY") && !strings.HasSuffix(trimmed, "[id]") && !strings.HasSuffix(trimmed, "r.[id]") {
			t.Errorf("an ORDER BY does not end in the id column: %q", trimmed)
		}
	}
}

func TestEveryFencedStatementCarriesTheToken(t *testing.T) {
	for _, dialect := range []Dialect{DialectPostgreSQL, DialectSQLServer} {
		statements, err := NewStatements(dialect, DefaultSchema())
		if err != nil {
			t.Fatalf("the statements did not render: %v", err)
		}

		for name, statement := range map[string]string{
			"renew":    statements.Renew,
			"complete": statements.Complete,
			"retry":    statements.Retry,
			"dead":     statements.Dead,
		} {
			for _, want := range []string{"'pull'", "'processing'"} {
				if !strings.Contains(statement, want) {
					t.Errorf("%s of %s does not contain %q", name, dialect, want)
				}
			}
		}
	}
}

func TestAMappedSchemaRenamesTheTableAndTheColumn(t *testing.T) {
	schema := DefaultSchema()
	schema.Table = "qb_messages"
	schema.State = "row_state"

	statements, err := NewStatements(DialectPostgreSQL, schema)
	if err != nil {
		t.Fatalf("the statements did not render: %v", err)
	}

	if !strings.Contains(statements.Complete, `"qb_messages"`) {
		t.Error("the completion does not name the mapped table")
	}

	if !strings.Contains(statements.Complete, `"row_state" = 'processed'`) {
		t.Error("the completion does not name the mapped column")
	}

	if strings.Contains(statements.Complete, `"inbox"`) {
		t.Error("the completion still names the default table")
	}
}

func TestAnUnsafeNameIsRejected(t *testing.T) {
	// A mapping comes from configuration, and configuration is not a trusted SQL fragment.
	for _, name := range []string{"inbox; DROP TABLE users", `in"box`, "in]box", ""} {
		schema := DefaultSchema()
		schema.Table = name

		if _, err := NewStatements(DialectPostgreSQL, schema); err == nil {
			t.Errorf("the name %q was accepted", name)
		}
	}
}

func TestAnIdentifierBecomesCanonicalText(t *testing.T) {
	// go-mssqldb hands a UNIQUEIDENTIFIER back as sixteen raw bytes, and SQL Server stores the
	// first three groups little-endian. pgx hands a uuid back as text already.
	raw := []byte{
		0x6b, 0x16, 0xcb, 0x7d, 0x49, 0xf5, 0xe1, 0x46,
		0xa8, 0x01, 0x88, 0xc7, 0xad, 0xa5, 0xdb, 0x1e,
	}

	if got := asUUID(raw); got != "7dcb166b-f549-46e1-a801-88c7ada5db1e" {
		t.Errorf("the identifier reads %q", got)
	}

	if got := asUUID("7dcb166b-f549-46e1-a801-88c7ada5db1e"); got != "7dcb166b-f549-46e1-a801-88c7ada5db1e" {
		t.Errorf("a text identifier changed to %q", got)
	}

	if got := asUUID(nil); got != "" {
		t.Errorf("a missing identifier reads %q", got)
	}
}
