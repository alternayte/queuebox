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

	for _, want := range []string{"FOR UPDATE SKIP LOCKED", `FROM "inbox"`, "$1"} {
		if !strings.Contains(statements.Claim, want) {
			t.Errorf("the claim does not contain %q", want)
		}
	}

	if strings.Contains(statements.Claim, ":source") {
		t.Error("the claim still carries a named parameter")
	}
}

func TestSQLServerClaimMatchesTheContract(t *testing.T) {
	statements, err := NewStatements(DialectSQLServer, DefaultSchema())
	if err != nil {
		t.Fatalf("the statements did not render: %v", err)
	}

	for _, want := range []string{"UPDLOCK, READPAST, ROWLOCK", "TOP (@p2)", "OUTPUT INSERTED.*"} {
		if !strings.Contains(statements.Claim, want) {
			t.Errorf("the claim does not contain %q", want)
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
