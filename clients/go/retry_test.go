package queuebox

import (
	"errors"
	"testing"
	"time"
)

func message(attempt int) Message {
	return Message{ID: "11111111-1111-1111-1111-111111111111", Source: "orders", Attempt: attempt}
}

var errFailure = errors.New("no")

func TestThePolicyRetriesBelowTheCeiling(t *testing.T) {
	policy := NewDefaultRetryPolicy(3, time.Second, time.Minute, 0)

	for _, attempt := range []int{0, 1, 2} {
		if action := policy.Decide(message(attempt), errFailure); !action.Retry {
			t.Errorf("the attempt %d did not retry", attempt)
		}
	}
}

func TestThePolicyDeadLettersAtTheCeiling(t *testing.T) {
	policy := NewDefaultRetryPolicy(3, time.Second, time.Minute, 0)

	for _, attempt := range []int{3, 9} {
		if action := policy.Decide(message(attempt), errFailure); action.Retry {
			t.Errorf("the attempt %d retried and the ceiling is three", attempt)
		}
	}
}

func TestTheBackoffDoublesAndThenStops(t *testing.T) {
	policy := NewDefaultRetryPolicy(10, time.Second, 4*time.Second, 0)

	for attempt, want := range map[int]time.Duration{
		0: time.Second,
		1: 2 * time.Second,
		2: 4 * time.Second,
		3: 4 * time.Second,
		8: 4 * time.Second,
	} {
		if got := policy.Decide(message(attempt), errFailure).Delay; got != want {
			t.Errorf("the attempt %d waits %s and the wait must be %s", attempt, got, want)
		}
	}
}

func TestTheJitterStaysInsideItsBand(t *testing.T) {
	policy := NewDefaultRetryPolicy(10, 10*time.Second, time.Minute, 0.2)

	for range 200 {
		delay := policy.Decide(message(0), errFailure).Delay

		if delay < 8*time.Second || delay > 12*time.Second {
			t.Fatalf("the delay %s left the band", delay)
		}
	}
}

func TestALargeAttemptDoesNotOverflowTheBackoff(t *testing.T) {
	policy := NewDefaultRetryPolicy(1<<30, time.Second, 5*time.Minute, 0)

	if got := policy.Decide(message(1_000_000), errFailure).Delay; got != 5*time.Minute {
		t.Errorf("the delay is %s and the ceiling is five minutes", got)
	}
}
