package contract_test

import (
	"context"
	"database/sql"
	"sync/atomic"
	"testing"
	"time"

	queuebox "github.com/alternayte/queuebox/clients/go"
)

// TestSQLServerClaimLockFailureIsTransient is item 19 of clients/contract-tests.md, from
// finding F-087. A claim lock failure (Msg 51000, from sp_getapplock returning negative) is a
// transient failure of the claim call, never a failure of a message. The worker must back off
// and retry the whole call, and it must never mark the message failed or dead.
//
// This item is SQL Server specific: PostgreSQL's claim takes no application lock of this kind.
//
// COVERAGE NOTE: this test proves the "never mark failed or dead" half of item 19, and that the
// worker survives past the 10 second sp_getapplock timeout without crashing. It does NOT prove
// the "must not retry immediately" half. Every claim attempt already blocks for the whole 10
// second sp_getapplock timeout before it fails, so the gap between two failing claims is large
// whether or not the worker adds a poll-interval backoff on top of it: the two cases are not
// distinguishable from outside with the fixed 10 second lock timeout the canonical statement
// declares. Proving the backoff itself needs a shorter, configurable lock timeout, which the
// canonical SQL Server claim text does not expose as a parameter.
func TestSQLServerClaimLockFailureIsTransient(t *testing.T) {
	h := &sqlServerHarness{}
	h.start(t)
	h.reset(t)

	id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-19", payload: "{}"})

	// Hold the per-source applock from outside the worker, for a session, with no timeout. Every
	// claim on this source must wait out the worker's own 10 second lock timeout and then see
	// sp_getapplock return negative.
	holderPool, err := sql.Open("sqlserver", h.dsn())
	if err != nil {
		t.Fatalf("the holder connection did not open: %v", err)
	}
	t.Cleanup(func() { _ = holderPool.Close() })

	// A session-scoped applock lives on one connection, so the acquire and the release must run
	// on the very same connection: sql.DB itself hands out a connection per call.
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

	var claimFailures atomic.Int64

	worker, err := queuebox.NewInboxWorker(h.db(), queuebox.Options{
		Source:       source,
		Dialect:      queuebox.DialectSQLServer,
		PollInterval: 200 * time.Millisecond,
		Logger:       countingLogger{failures: &claimFailures},
	})
	if err != nil {
		t.Fatalf("the worker did not build: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)

	go func() {
		done <- worker.Run(ctx, func(context.Context, queuebox.Message, *sql.Tx) error { return nil })
	}()

	// Longer than the worker's 10 second sp_getapplock timeout: the message must still be
	// untouched, and the worker must still be running, having backed off rather than crashed.
	time.Sleep(35 * time.Second)

	state, attempt, lastError := h.readRow(t, id)

	if state != "pending" {
		t.Errorf("the state is %q and it must still be pending", state)
	}

	if attempt != 0 {
		t.Errorf("the attempt is %d and it must still be zero", attempt)
	}

	if lastError != nil {
		t.Errorf("the last error is %q and it must still be unset", *lastError)
	}

	if claimFailures.Load() == 0 {
		t.Error("the claim must have reported at least one transient failure while the lock was held")
	}

	// Release the lock. The worker did not crash, so the very next poll must succeed.
	if _, err := holder.ExecContext(context.Background(),
		"EXEC sp_releaseapplock @Resource = @p1, @LockOwner = 'Session';", source); err != nil {
		t.Fatalf("the applock did not release: %v", err)
	}

	deadline := time.Now().Add(30 * time.Second)

	for time.Now().Before(deadline) {
		state, attempt, lastError = h.readRow(t, id)
		if state == "processed" {
			break
		}

		time.Sleep(200 * time.Millisecond)
	}

	cancel()

	if err := <-done; err != nil {
		t.Fatalf("the worker stopped with an error: %v", err)
	}

	if state != "processed" {
		t.Fatalf("the state is %q and it must be processed", state)
	}

	if attempt != 0 {
		t.Errorf("the attempt is %d and it must still be zero: a lock failure must not spend an attempt", attempt)
	}

	if lastError != nil {
		t.Errorf("the last error is %q and it must still be unset", *lastError)
	}
}

// countingLogger counts every claim failure the worker reports, through the "The claim failed."
// message worker.go writes when the claim returns an error.
type countingLogger struct {
	failures *atomic.Int64
}

func (l countingLogger) Info(string, map[string]any) {}
func (l countingLogger) Warn(string, map[string]any) {}

func (l countingLogger) Error(message string, _ map[string]any) {
	if message == "The claim failed." {
		l.failures.Add(1)
	}
}
