package contract_test

import (
	"context"
	"database/sql"
	"strings"
	"testing"
	"time"

	queuebox "github.com/alternayte/queuebox/clients/go"
)

// TestSQLServerLockTimeoutBeatsDriverDefault is the regression test for finding F-087. The
// canonical claim (examples/pull/sql/sqlserver/claim.sql) sets sp_getapplock's own @LockTimeout.
// That value must sit below every driver default, so the server always raises Msg 51000 before a
// driver aborts the call on its own. A client-side abort does NOT roll back the server-side
// transaction: the lock uses @LockOwner = 'Transaction', so an abandoned transaction keeps the
// per-source claim lock until the connection resets, and every later claim on that source then
// waits its own full timeout and aborts the same way.
//
// go-mssqldb inherits the deadline of the caller's context. This test sets a context deadline of
// 20 seconds: below the 30 second minimum this fix requires callers to set, and (before the fix)
// below the canonical statement's own 30 second lock timeout, so a pre-fix run always sees the
// context deadline expire first with no Msg 51000 in sight. After the fix (10 second lock
// timeout) the 20 second context deadline is well above the lock timeout, so the claim must
// finish with Msg 51000 well inside the context deadline.
func TestSQLServerLockTimeoutBeatsDriverDefault(t *testing.T) {
	h := &sqlServerHarness{}
	h.start(t)
	h.reset(t)

	// Hold the per-source applock from outside the worker, for a session, with no timeout.
	holderPool, err := sql.Open("sqlserver", h.dsn())
	if err != nil {
		t.Fatalf("the holder connection did not open: %v", err)
	}
	t.Cleanup(func() { _ = holderPool.Close() })

	holder, err := holderPool.Conn(context.Background())
	if err != nil {
		t.Fatalf("the holder connection did not check out: %v", err)
	}
	t.Cleanup(func() { _ = holder.Close() })

	if _, err := holder.ExecContext(context.Background(),
		"EXEC sp_getapplock @Resource = @p1, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = -1;",
		source,
	); err != nil {
		t.Fatalf("the applock did not acquire: %v", err)
	}
	t.Cleanup(func() {
		_, _ = holder.ExecContext(context.Background(),
			"EXEC sp_releaseapplock @Resource = @p1, @LockOwner = 'Session';", source)
	})

	statements, err := queuebox.NewStatements(queuebox.DialectSQLServer, queuebox.DefaultSchema())
	if err != nil {
		t.Fatalf("the statements did not build: %v", err)
	}

	// Below 30 seconds, as the fix's documentation requires, and above the fixed 10 second lock
	// timeout, so a post-fix claim has time to see Msg 51000 well before the context aborts it.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	started := time.Now()
	_, err = h.db().ExecContext(ctx, statements.Claim, source, 10, 30000, 100)
	elapsed := time.Since(started)

	if err == nil {
		t.Fatal("the claim must fail while another session holds the per-source applock")
	}

	if strings.Contains(err.Error(), "context deadline exceeded") {
		t.Fatalf("the claim aborted on the driver's own context deadline after %s, instead of "+
			"seeing Msg 51000 from sp_getapplock: %v", elapsed, err)
	}

	if !strings.Contains(err.Error(), "51000") {
		t.Fatalf("the claim failed with %q, and it must fail with Msg 51000", err.Error())
	}

	// The lock timeout is 10 seconds: the claim must fail near that, never near the 20 second
	// context deadline.
	if elapsed >= 15*time.Second {
		t.Fatalf("the claim took %s to fail, which is too close to the 20 second context "+
			"deadline for the 10 second server-side lock timeout to have fired first", elapsed)
	}
}
