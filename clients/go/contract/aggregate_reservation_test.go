package contract_test

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	queuebox "github.com/alternayte/queuebox/clients/go"
)

// TestOneAggregateNeverRunsTwoHandlers is item 18 of clients/contract-tests.md, from finding
// F-087. The claim must never hand out two rows of one aggregate to run at one time, even under
// two competing workers.
func TestOneAggregateNeverRunsTwoHandlers(t *testing.T) {
	for name, build := range map[string]func() harness{
		"postgresql": func() harness { return &postgresHarness{} },
		"sqlserver":  func() harness { return &sqlServerHarness{} },
	} {
		t.Run(name, func(t *testing.T) {
			h := build()
			h.start(t)
			h.reset(t)

			r := runner{t: t, h: h}
			aggregate := "agg-1"

			for i := range 4 {
				h.insertPending(t, pendingRow{
					source:         source,
					idempotencyKey: fmt.Sprintf("key-18-single-%d", i),
					payload:        "{}",
					aggregateID:    &aggregate,
				})
			}

			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			defer cancel()

			var (
				mutex   sync.Mutex
				windows [][2]time.Time
			)

			handle := func(_ context.Context, _ queuebox.Message, _ *sql.Tx) error {
				start := time.Now()
				time.Sleep(200 * time.Millisecond)

				mutex.Lock()
				windows = append(windows, [2]time.Time{start, time.Now()})
				mutex.Unlock()

				return nil
			}

			var group sync.WaitGroup

			for range 2 {
				group.Add(1)

				go func() {
					defer group.Done()
					_ = r.worker(t, queuebox.Options{BatchSize: 4}).Run(ctx, handle)
				}()
			}

			deadline := time.Now().Add(90 * time.Second)
			for {
				mutex.Lock()
				done := len(windows)
				mutex.Unlock()

				if done >= 4 || time.Now().After(deadline) {
					break
				}

				time.Sleep(50 * time.Millisecond)
			}

			cancel()
			group.Wait()

			mutex.Lock()
			defer mutex.Unlock()

			if len(windows) != 4 {
				t.Fatalf("the handler ran %d times and it must run 4 times", len(windows))
			}

			sort.Slice(windows, func(i, j int) bool { return windows[i][0].Before(windows[j][0]) })

			for i := 1; i < len(windows); i++ {
				if windows[i][0].Before(windows[i-1][1]) {
					t.Fatalf("handler %d started at %v, before handler %d ended at %v",
						i, windows[i][0], i-1, windows[i-1][1])
				}
			}
		})
	}
}

// TestTwoAggregatesDoRunAtOneTime is the other half of item 18: the reservation must be scoped
// to one aggregate, and must not serialize the whole source.
func TestTwoAggregatesDoRunAtOneTime(t *testing.T) {
	for name, build := range map[string]func() harness{
		"postgresql": func() harness { return &postgresHarness{} },
		"sqlserver":  func() harness { return &sqlServerHarness{} },
	} {
		t.Run(name, func(t *testing.T) {
			h := build()
			h.start(t)
			h.reset(t)

			r := runner{t: t, h: h}

			aggregate1 := "agg-1"
			aggregate2 := "agg-2"

			for i := range 2 {
				h.insertPending(t, pendingRow{
					source:         source,
					idempotencyKey: fmt.Sprintf("key-18-multi-a-%d", i),
					payload:        "{}",
					aggregateID:    &aggregate1,
				})
				h.insertPending(t, pendingRow{
					source:         source,
					idempotencyKey: fmt.Sprintf("key-18-multi-b-%d", i),
					payload:        "{}",
					aggregateID:    &aggregate2,
				})
			}

			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			defer cancel()

			var (
				inFlight, peak, handled atomic.Int64
			)

			handle := func(_ context.Context, _ queuebox.Message, _ *sql.Tx) error {
				now := inFlight.Add(1)

				for {
					seen := peak.Load()
					if now <= seen || peak.CompareAndSwap(seen, now) {
						break
					}
				}

				time.Sleep(200 * time.Millisecond)
				inFlight.Add(-1)
				handled.Add(1)

				return nil
			}

			var group sync.WaitGroup

			for range 2 {
				group.Add(1)

				go func() {
					defer group.Done()
					_ = r.worker(t, queuebox.Options{BatchSize: 4}).Run(ctx, handle)
				}()
			}

			deadline := time.Now().Add(90 * time.Second)
			for handled.Load() < 4 && time.Now().Before(deadline) {
				time.Sleep(50 * time.Millisecond)
			}

			cancel()
			group.Wait()

			if got := handled.Load(); got != 4 {
				t.Fatalf("the handler ran %d times and it must run 4 times", got)
			}

			if got := peak.Load(); got != 2 {
				t.Fatalf("the peak concurrency was %d and it must be 2, one per aggregate", got)
			}
		})
	}
}
