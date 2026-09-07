package contract_test

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	queuebox "github.com/alternayte/queuebox/clients/go"
)

const source = "orders"

var errHandler = errors.New("the handler failed")

// TestTheContract runs the list of clients/contract-tests.md against every harness. A difference
// between two dialects must be a difference of idiom, never of guarantee.
func TestTheContract(t *testing.T) {
	for name, build := range map[string]func() harness{
		"postgresql": func() harness { return &postgresHarness{} },
		"sqlserver":  func() harness { return &sqlServerHarness{} },
	} {
		t.Run(name, func(t *testing.T) {
			h := build()
			h.start(t)
			runContract(t, h)
		})
	}
}

type runner struct {
	t *testing.T
	h harness
}

func (r runner) worker(t *testing.T, options queuebox.Options) *queuebox.InboxWorker {
	t.Helper()

	options.Source = source
	options.Dialect = r.h.dialect()

	if options.PollInterval == 0 {
		options.PollInterval = 50 * time.Millisecond
	}

	if options.ShutdownGrace == 0 {
		options.ShutdownGrace = 10 * time.Second
	}

	worker, err := queuebox.NewInboxWorker(r.h.db(), options)
	if err != nil {
		t.Fatalf("the worker did not build: %v", err)
	}

	return worker
}

// runUntil works until the handler ran the expected number of times, then stops the worker.
func (r runner) runUntil(t *testing.T, expected int, options queuebox.Options, handler queuebox.Handler) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	var (
		handled atomic.Int64
		reached = make(chan struct{})
		once    sync.Once
	)

	done := make(chan error, 1)

	go func() {
		done <- r.worker(t, options).Run(ctx, func(ctx context.Context, message queuebox.Message, tx *sql.Tx) error {
			err := handler(ctx, message, tx)

			if handled.Add(1) >= int64(expected) {
				once.Do(func() { close(reached) })
			}

			return err
		})
	}()

	select {
	case <-reached:
	case <-ctx.Done():
		t.Fatal("the handler did not run often enough")
	}

	// The failure path runs after the handler returned, so give it room to finish.
	time.Sleep(500 * time.Millisecond)
	cancel()

	if err := <-done; err != nil {
		t.Fatalf("the worker stopped with an error: %v", err)
	}
}

func (r runner) writeApplicationRow(tx *sql.Tx, key string) error {
	_, err := tx.Exec(r.h.insertApplicationRow(), key)

	return err
}

func runContract(t *testing.T, h harness) {
	r := runner{t: t, h: h}

	reset := func(t *testing.T) {
		t.Helper()
		h.reset(t)
	}

	// Item 1.
	t.Run("gives the handler every field", func(t *testing.T) {
		reset(t)

		aggregate, event, correlation := "agg-1", "OrderPlaced", "corr-1"
		id := h.insertPending(t, pendingRow{
			source: source, idempotencyKey: "key-1", payload: `{"id":"order-1","total":42}`,
			aggregateID: &aggregate, eventType: &event, correlationID: &correlation, attempt: 2,
		})

		var seen queuebox.Message

		r.runUntil(t, 1, queuebox.Options{}, func(_ context.Context, message queuebox.Message, _ *sql.Tx) error {
			seen = message
			return nil
		})

		if !strings.EqualFold(seen.ID, id) {
			t.Errorf("the identifier is %q and it must be %q", seen.ID, id)
		}

		if seen.Source != source || seen.IdempotencyKey != "key-1" || seen.Attempt != 2 {
			t.Errorf("a field is wrong: %+v", seen)
		}

		if seen.AggregateID == nil || *seen.AggregateID != aggregate {
			t.Error("the aggregate identifier is wrong")
		}

		if seen.EventType == nil || *seen.EventType != event {
			t.Error("the event type is wrong")
		}

		if seen.CorrelationID == nil || *seen.CorrelationID != correlation {
			t.Error("the correlation identifier is wrong")
		}

		var payload struct {
			ID    string `json:"id"`
			Total int    `json:"total"`
		}

		if err := seen.UnmarshalPayload(&payload); err != nil {
			t.Fatalf("the payload did not parse: %v", err)
		}

		if payload.ID != "order-1" || payload.Total != 42 {
			t.Errorf("the payload is %+v", payload)
		}
	})

	// Item 1, the nullable fields.
	t.Run("gives the nullable fields as nil", func(t *testing.T) {
		reset(t)
		h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-null", payload: `{"a":1}`})

		var seen queuebox.Message

		r.runUntil(t, 1, queuebox.Options{}, func(_ context.Context, message queuebox.Message, _ *sql.Tx) error {
			seen = message
			return nil
		})

		if seen.AggregateID != nil || seen.EventType != nil || seen.CorrelationID != nil || seen.Attempt != 0 {
			t.Errorf("a nullable field is not nil: %+v", seen)
		}
	})

	// Item 2. This is the whole point of the pull path.
	t.Run("commits the writes and the completion together", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-2", payload: "{}"})

		r.runUntil(t, 1, queuebox.Options{}, func(_ context.Context, _ queuebox.Message, tx *sql.Tx) error {
			return r.writeApplicationRow(tx, "row-2")
		})

		if state, _, _ := h.readRow(t, id); state != "processed" {
			t.Errorf("the state is %q and it must be processed", state)
		}

		if count := h.countApplicationRows(t, "row-2"); count != 1 {
			t.Errorf("the application wrote %d rows and it must write one", count)
		}
	})

	// Item 3.
	t.Run("leaves no write behind when the handler fails", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-3", payload: "{}"})

		r.runUntil(t, 1, queuebox.Options{}, func(_ context.Context, _ queuebox.Message, tx *sql.Tx) error {
			if err := r.writeApplicationRow(tx, "row-3"); err != nil {
				return err
			}

			return errHandler
		})

		if count := h.countApplicationRows(t, "row-3"); count != 0 {
			t.Errorf("the application left %d rows behind", count)
		}

		if state, _, _ := h.readRow(t, id); state == "processed" {
			t.Error("the message was completed although the handler failed")
		}
	})

	// Item 4.
	t.Run("rolls the writes back when the completion affects no row", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-4", payload: "{}"})

		// The lease is long, so no renewal notices the theft. The completion must catch it.
		r.runUntil(t, 1, queuebox.Options{LeaseMS: 600_000}, func(_ context.Context, message queuebox.Message, tx *sql.Tx) error {
			if err := r.writeApplicationRow(tx, "row-4"); err != nil {
				return err
			}

			h.stealClaim(t, message.ID)

			return nil
		})

		if count := h.countApplicationRows(t, "row-4"); count != 0 {
			t.Errorf("the application left %d rows behind", count)
		}

		if state, _, _ := h.readRow(t, id); state != "processing" {
			t.Errorf("the state is %q and the thief must still own it", state)
		}
	})

	// Item 5.
	t.Run("never gives one message to two workers", func(t *testing.T) {
		reset(t)

		const total = 20
		for i := range total {
			h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-5-" + strings.Repeat("x", i), payload: "{}"})
		}

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()

		var (
			mutex   sync.Mutex
			seen    = map[string]int{}
			reached = make(chan struct{})
			once    sync.Once
		)

		handle := func(_ context.Context, message queuebox.Message, _ *sql.Tx) error {
			mutex.Lock()
			seen[strings.ToLower(message.ID)]++
			count := len(seen)
			mutex.Unlock()

			if count >= total {
				once.Do(func() { close(reached) })
			}

			return nil
		}

		var group sync.WaitGroup

		for range 2 {
			group.Add(1)

			go func() {
				defer group.Done()
				_ = r.worker(t, queuebox.Options{BatchSize: 3}).Run(ctx, handle)
			}()
		}

		select {
		case <-reached:
		case <-ctx.Done():
			t.Fatal("the workers did not take every message")
		}

		cancel()
		group.Wait()

		mutex.Lock()
		defer mutex.Unlock()

		if len(seen) != total {
			t.Errorf("the workers took %d distinct messages and there are %d", len(seen), total)
		}

		for id, count := range seen {
			if count != 1 {
				t.Errorf("the message %s went to %d handlers", id, count)
			}
		}
	})

	// Item 6.
	t.Run("renews and keeps ownership across a slow handler", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-6", payload: "{}"})

		// The lease is one second and the renewal runs every third of it, so a handler of three
		// seconds needs several renewals to survive.
		r.runUntil(t, 1, queuebox.Options{LeaseMS: 1000}, func(_ context.Context, _ queuebox.Message, tx *sql.Tx) error {
			time.Sleep(3 * time.Second)

			return r.writeApplicationRow(tx, "row-6")
		})

		if state, _, _ := h.readRow(t, id); state != "processed" {
			t.Errorf("the state is %q and it must be processed", state)
		}

		if count := h.countApplicationRows(t, "row-6"); count != 1 {
			t.Errorf("the application wrote %d rows", count)
		}
	})

	// Item 7.
	t.Run("cancels the handler and completes nothing when a renewal is lost", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-7", payload: "{}"})

		var cancelled atomic.Bool

		r.runUntil(t, 1, queuebox.Options{LeaseMS: 1000}, func(ctx context.Context, message queuebox.Message, _ *sql.Tx) error {
			h.stealClaim(t, message.ID)

			select {
			case <-ctx.Done():
				cancelled.Store(true)
				return ctx.Err()
			case <-time.After(30 * time.Second):
				return nil
			}
		})

		if !cancelled.Load() {
			t.Error("the renewal did not cancel the handler")
		}

		if state, _, _ := h.readRow(t, id); state != "processing" {
			t.Errorf("the state is %q and the thief must still own it", state)
		}
	})

	// Item 8.
	t.Run("returns an abandoned message after the lease expires", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-8", payload: "{}"})

		ctx, cancel := context.WithCancel(context.Background())
		reached := make(chan struct{})
		done := make(chan error, 1)

		// The shutdown gives no grace at all, which is how a killed worker behaves.
		worker := r.worker(t, queuebox.Options{LeaseMS: 1000, ShutdownGrace: time.Nanosecond})

		go func() {
			done <- worker.Run(ctx, func(ctx context.Context, _ queuebox.Message, _ *sql.Tx) error {
				close(reached)
				<-ctx.Done()

				return ctx.Err()
			})
		}()

		<-reached
		cancel()
		<-done

		// The message was abandoned, not completed, and the shutdown spent no attempt on it.
		state, attempt, _ := h.readRow(t, id)
		if state != "processing" || attempt != 0 {
			t.Errorf("the state is %q and the attempt is %d", state, attempt)
		}

		// The lease expires, so a second worker takes the message back.
		var returned atomic.Bool

		r.runUntil(t, 1, queuebox.Options{}, func(_ context.Context, message queuebox.Message, _ *sql.Tx) error {
			returned.Store(strings.EqualFold(message.ID, id))
			return nil
		})

		if !returned.Load() {
			t.Error("the abandoned message did not return")
		}
	})

	// Item 9.
	t.Run("increments the attempt and applies the backoff on a retry", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-9", payload: "{}"})
		before := h.readScheduledAt(t, id)

		policy := queuebox.NewDefaultRetryPolicy(5, time.Minute, time.Minute, 0)

		r.runUntil(t, 1, queuebox.Options{RetryPolicy: policy}, func(context.Context, queuebox.Message, *sql.Tx) error {
			return errHandler
		})

		state, attempt, lastError := h.readRow(t, id)

		if state != "pending" || attempt != 1 || lastError == nil {
			t.Errorf("the state is %q, the attempt is %d and the error is %v", state, attempt, lastError)
		}

		if after := h.readScheduledAt(t, id); !after.After(before.Add(30 * time.Second)) {
			t.Errorf("the schedule moved from %s to %s, which is not a backoff", before, after)
		}
	})

	// Item 10.
	t.Run("reaches the dead letter at the ceiling", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-10", payload: "{}", attempt: 3})

		policy := queuebox.NewDefaultRetryPolicy(3, time.Second, time.Second, 0)

		r.runUntil(t, 1, queuebox.Options{RetryPolicy: policy}, func(context.Context, queuebox.Message, *sql.Tx) error {
			return errHandler
		})

		state, _, lastError := h.readRow(t, id)

		if state != "dead" || lastError == nil {
			t.Errorf("the state is %q and the error is %v", state, lastError)
		}
	})

	// Item 11.
	t.Run("changes nothing under a stale token", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-11", payload: "{}"})

		// Another worker owns the message under a token of its own.
		h.stealClaim(t, id)

		stale := "11111111-2222-3333-4444-555555555555"
		statements := mustStatements(t, h.dialect())

		for name, one := range map[string]struct {
			text string
			args []any
		}{
			"complete": {statements.Complete, []any{id, stale}},
			"retry":    {statements.Retry, []any{1000, "no", id, stale}},
			"dead":     {statements.Dead, []any{"no", id, stale}},
		} {
			result, err := h.db().Exec(one.text, one.args...)
			if err != nil {
				t.Fatalf("the %s statement failed: %v", name, err)
			}

			affected, err := result.RowsAffected()
			if err != nil {
				t.Fatalf("the %s count is unknown: %v", name, err)
			}

			if affected != 0 {
				t.Errorf("the %s statement changed %d rows under a stale token", name, affected)
			}
		}

		state, attempt, lastError := h.readRow(t, id)
		if state != "processing" || attempt != 0 || lastError != nil {
			t.Errorf("the row changed: state %q, attempt %d, error %v", state, attempt, lastError)
		}
	})

	// Item 12.
	t.Run("claims nothing new after a stop and abandons rather than completes", func(t *testing.T) {
		reset(t)
		first := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-12-a", payload: "{}"})
		second := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-12-b", payload: "{}"})

		ctx, cancel := context.WithCancel(context.Background())
		reached := make(chan struct{})
		done := make(chan error, 1)

		// A batch of one, so the second message is still pending when the stop arrives.
		worker := r.worker(t, queuebox.Options{BatchSize: 1, LeaseMS: 60_000, ShutdownGrace: time.Nanosecond})

		go func() {
			done <- worker.Run(ctx, func(ctx context.Context, _ queuebox.Message, _ *sql.Tx) error {
				close(reached)
				<-ctx.Done()

				return ctx.Err()
			})
		}()

		<-reached
		cancel()
		<-done

		// The two rows carry the same scheduled_at, so the claim order is not decided. The
		// guarantee is about the counts: the stop completes nothing and claims nothing new, so
		// exactly one row is in flight and the other is untouched.
		firstState, _, _ := h.readRow(t, first)
		secondState, _, _ := h.readRow(t, second)

		processing := 0
		pending := 0

		for _, state := range []string{firstState, secondState} {
			switch state {
			case "processed":
				t.Error("the stop completed a message")
			case "processing":
				processing++
			case "pending":
				pending++
			}
		}

		if processing != 1 || pending != 1 {
			t.Errorf("the states are %q and %q", firstState, secondState)
		}
	})

	// Item 13.
	t.Run("works against a mapped table and mapped column names", func(t *testing.T) {
		reset(t)

		schema := h.createMappedSchema(t)
		id := h.insertMappedPending(t, schema, source, "key-13", `{"a":1}`)

		r.runUntil(t, 1, queuebox.Options{Schema: &schema}, func(_ context.Context, message queuebox.Message, tx *sql.Tx) error {
			if !strings.EqualFold(message.ID, id) {
				t.Errorf("the identifier is %q and it must be %q", message.ID, id)
			}

			return r.writeApplicationRow(tx, "row-13")
		})

		if state := h.readMappedState(t, schema, id); state != "processed" {
			t.Errorf("the mapped state is %q", state)
		}

		if count := h.countApplicationRows(t, "row-13"); count != 1 {
			t.Errorf("the application wrote %d rows", count)
		}
	})

	// Item 15. The failure text reaches the database, so a secret must not travel with it.
	t.Run("keeps a secret out of the last error column", func(t *testing.T) {
		reset(t)
		id := h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-15", payload: "{}"})

		r.runUntil(t, 1, queuebox.Options{}, func(context.Context, queuebox.Message, *sql.Tx) error {
			return errors.New("connect failed: Host=db;Password=hunter2;Database=queuebox")
		})

		_, _, lastError := h.readRow(t, id)

		if lastError == nil {
			t.Fatal("the failure recorded no text")
		}

		if strings.Contains(*lastError, "hunter2") {
			t.Errorf("the password printed: %q", *lastError)
		}
	})

	// Item 16.
	t.Run("does not start a polling storm on an empty claim", func(t *testing.T) {
		reset(t)

		// The table is empty, so the claim is the only statement that runs.
		pool, counter := countingPool(t, h)

		worker, err := queuebox.NewInboxWorker(pool, queuebox.Options{
			Source:       source,
			Dialect:      h.dialect(),
			PollInterval: 200 * time.Millisecond,
		})
		if err != nil {
			t.Fatalf("the worker did not build: %v", err)
		}

		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan error, 1)

		go func() {
			done <- worker.Run(ctx, func(context.Context, queuebox.Message, *sql.Tx) error { return nil })
		}()

		time.Sleep(2 * time.Second)
		cancel()
		<-done

		// Two seconds at two hundred milliseconds is about ten claims. The band is wide, because
		// a container is not a clock.
		if claims := counter.Load(); claims < 4 || claims > 20 {
			t.Errorf("the worker ran %d claims in two seconds", claims)
		}
	})

	// Item 17.
	t.Run("never holds more than the batch", func(t *testing.T) {
		reset(t)

		const total = 12
		for i := range total {
			h.insertPending(t, pendingRow{source: source, idempotencyKey: "key-17-" + strings.Repeat("y", i), payload: "{}"})
		}

		var inFlight, highWater atomic.Int64

		r.runUntil(t, total, queuebox.Options{BatchSize: 3}, func(context.Context, queuebox.Message, *sql.Tx) error {
			now := inFlight.Add(1)

			for {
				seen := highWater.Load()
				if now <= seen || highWater.CompareAndSwap(seen, now) {
					break
				}
			}

			time.Sleep(50 * time.Millisecond)
			inFlight.Add(-1)

			return nil
		})

		if high := highWater.Load(); high > 3 {
			t.Errorf("the worker held %d messages and the batch is three", high)
		}
	})
}
