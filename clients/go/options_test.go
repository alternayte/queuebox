package queuebox

import (
	"testing"
	"time"
)

func TestTheDefaultsAreUsable(t *testing.T) {
	resolved, schema, policy, logger, err := Options{Source: "orders"}.resolve()
	if err != nil {
		t.Fatalf("the options did not resolve: %v", err)
	}

	if resolved.batchSize != 10 || resolved.leaseMS != 30_000 {
		t.Errorf("the defaults are batch %d and lease %d", resolved.batchSize, resolved.leaseMS)
	}

	if resolved.renewalInterval != 10*time.Second {
		t.Errorf("the renewal interval is %s and a third of the lease is ten seconds", resolved.renewalInterval)
	}

	if schema.Table != "inbox" || policy == nil || logger == nil {
		t.Error("a default is missing")
	}
}

func TestTheConcurrencyNeverPassesTheBatch(t *testing.T) {
	for _, testCase := range []struct {
		batch, concurrency, want int
	}{
		{batch: 4, concurrency: 16, want: 4},
		{batch: 4, concurrency: 0, want: 1},
		{batch: 4, concurrency: 2, want: 2},
	} {
		resolved, _, _, _, err := Options{Source: "orders", BatchSize: testCase.batch, MaxConcurrency: testCase.concurrency}.resolve()
		if err != nil {
			t.Fatalf("the options did not resolve: %v", err)
		}

		if resolved.concurrency != testCase.want {
			t.Errorf("the concurrency is %d and it must be %d", resolved.concurrency, testCase.want)
		}
	}
}

func TestConcurrencyDefaultsToOne(t *testing.T) {
	resolved, _, _, _, err := Options{Source: "s"}.resolve()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}
	if resolved.concurrency != 1 {
		t.Fatalf("concurrency was %d, want 1", resolved.concurrency)
	}
}

func TestExplicitConcurrencySurvives(t *testing.T) {
	resolved, _, _, _, err := Options{Source: "s", MaxConcurrency: 5}.resolve()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}
	if resolved.concurrency != 5 {
		t.Fatalf("concurrency was %d, want 5", resolved.concurrency)
	}
}

func TestASettingThatCannotWorkIsRejected(t *testing.T) {
	for name, options := range map[string]Options{
		"no source":       {Source: "  "},
		"negative batch":  {Source: "orders", BatchSize: -1},
		"a lease of two":  {Source: "orders", LeaseMS: 2},
		"negative worker": {Source: "orders", MaxConcurrency: -1},
		"negative poll":   {Source: "orders", PollInterval: -time.Second},
	} {
		if _, _, _, _, err := options.resolve(); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}
}
