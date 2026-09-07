package queuebox

import (
	"math"
	"math/rand/v2"
	"time"
)

// FailureAction states what the library does with a message whose handler returned an error.
type FailureAction struct {
	// Retry is true to run the retry statement, and false to run the dead-letter statement.
	Retry bool
	// Delay is how long the retry waits before the message can be claimed again.
	Delay time.Duration
}

// RetryAfter returns the message to pending after a delay.
func RetryAfter(delay time.Duration) FailureAction {
	if delay < 0 {
		delay = 0
	}

	return FailureAction{Retry: true, Delay: delay}
}

// DeadLetter moves the message to the dead letter and stops.
func DeadLetter() FailureAction {
	return FailureAction{}
}

// RetryPolicy decides what happens to a message whose handler returned an error.
//
// The library exposes the decision, because only the application knows that a validation error
// must never be retried while a timeout must.
type RetryPolicy interface {
	Decide(message Message, failure error) FailureAction
}

// RetryPolicyFunc lets a plain function be a RetryPolicy.
type RetryPolicyFunc func(message Message, failure error) FailureAction

// Decide calls the function.
func (f RetryPolicyFunc) Decide(message Message, failure error) FailureAction {
	return f(message, failure)
}

type defaultRetryPolicy struct {
	maxAttempts int
	baseDelay   time.Duration
	maxDelay    time.Duration
	jitter      float64
}

// NewDefaultRetryPolicy retries while the attempt is below the ceiling, with an exponential
// backoff and jitter, and dead-letters after that.
//
// The row starts at attempt zero, so the ceiling allows maxAttempts plus one deliveries.
func NewDefaultRetryPolicy(maxAttempts int, baseDelay, maxDelay time.Duration, jitter float64) RetryPolicy {
	return defaultRetryPolicy{maxAttempts: maxAttempts, baseDelay: baseDelay, maxDelay: maxDelay, jitter: jitter}
}

func (p defaultRetryPolicy) Decide(message Message, _ error) FailureAction {
	if message.Attempt >= p.maxAttempts {
		return DeadLetter()
	}

	// A large attempt must not overflow the shift, so the exponent stops where the ceiling is
	// certainly reached.
	exponent := min(message.Attempt, 30)
	scaled := float64(p.baseDelay) * math.Pow(2, float64(exponent))
	bounded := time.Duration(math.Min(scaled, float64(p.maxDelay)))

	if p.jitter <= 0 {
		return RetryAfter(bounded)
	}

	// A full band around the delay: one worker's backoff never lines up with another's.
	offset := (rand.Float64()*2 - 1) * p.jitter * float64(bounded)

	return RetryAfter(bounded + time.Duration(offset))
}
