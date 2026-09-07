package queuebox

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// Logger is the logger the library writes to. It prints nothing without one.
type Logger interface {
	Info(message string, fields map[string]any)
	Warn(message string, fields map[string]any)
	Error(message string, fields map[string]any)
}

type noopLogger struct{}

func (noopLogger) Info(string, map[string]any)  {}
func (noopLogger) Warn(string, map[string]any)  {}
func (noopLogger) Error(string, map[string]any) {}

// Options holds the settings of one worker.
type Options struct {
	// Source is the source whose messages this worker takes. It is mandatory.
	Source string
	// BatchSize is the largest number of messages one claim takes. The default is ten.
	BatchSize int
	// LeaseMS is the lease duration in milliseconds. The renewal runs every third of it.
	// The default is thirty thousand.
	LeaseMS int
	// MaxConcurrency is the largest number of handlers that run at one time. It never passes the
	// batch size. The default is the batch size.
	MaxConcurrency int
	// PollInterval is how long the worker waits after a claim that returned nothing.
	// The default is one second.
	PollInterval time.Duration
	// ShutdownGrace is how long a stop waits for the handlers that already run.
	// The default is thirty seconds.
	ShutdownGrace time.Duration
	// Dialect is the database dialect. The default is PostgreSQL.
	Dialect Dialect
	// Schema names the table and the columns. The default is the QueueBox V6 names.
	Schema *Schema
	// RetryPolicy decides what happens to a message whose handler returned an error.
	RetryPolicy RetryPolicy
	// Logger is the caller's logger.
	Logger Logger
}

type resolvedOptions struct {
	source          string
	batchSize       int
	leaseMS         int
	concurrency     int
	pollInterval    time.Duration
	shutdownGrace   time.Duration
	renewalInterval time.Duration
}

func (o Options) resolve() (resolvedOptions, Schema, RetryPolicy, Logger, error) {
	if strings.TrimSpace(o.Source) == "" {
		return resolvedOptions{}, Schema{}, nil, nil, errors.New("the source is mandatory")
	}

	batchSize := o.BatchSize
	if batchSize == 0 {
		batchSize = 10
	}

	leaseMS := o.LeaseMS
	if leaseMS == 0 {
		leaseMS = 30_000
	}

	pollInterval := o.PollInterval
	if pollInterval == 0 {
		pollInterval = time.Second
	}

	shutdownGrace := o.ShutdownGrace
	if shutdownGrace == 0 {
		shutdownGrace = 30 * time.Second
	}

	if batchSize < 0 {
		return resolvedOptions{}, Schema{}, nil, nil, fmt.Errorf("the batch size %d is not positive", batchSize)
	}

	// The renewal runs every third of the lease, so a shorter lease has no interval at all.
	if leaseMS < 3 {
		return resolvedOptions{}, Schema{}, nil, nil, fmt.Errorf("the lease of %d milliseconds leaves no room for a renewal", leaseMS)
	}

	if o.MaxConcurrency < 0 {
		return resolvedOptions{}, Schema{}, nil, nil, fmt.Errorf("the concurrency %d is not positive", o.MaxConcurrency)
	}

	if pollInterval < 0 {
		return resolvedOptions{}, Schema{}, nil, nil, errors.New("the poll interval is negative")
	}

	if shutdownGrace < 0 {
		return resolvedOptions{}, Schema{}, nil, nil, errors.New("the shutdown grace is negative")
	}

	concurrency := o.MaxConcurrency
	if concurrency == 0 {
		concurrency = batchSize
	}

	// Bounded memory: never hold more than the configured batch.
	concurrency = min(concurrency, batchSize)

	schema := DefaultSchema()
	if o.Schema != nil {
		schema = *o.Schema
	}

	policy := o.RetryPolicy
	if policy == nil {
		policy = NewDefaultRetryPolicy(5, time.Second, 5*time.Minute, 0.2)
	}

	var logger Logger = noopLogger{}
	if o.Logger != nil {
		logger = o.Logger
	}

	return resolvedOptions{
		source:          o.Source,
		batchSize:       batchSize,
		leaseMS:         leaseMS,
		concurrency:     concurrency,
		pollInterval:    pollInterval,
		shutdownGrace:   shutdownGrace,
		renewalInterval: max(time.Millisecond, time.Duration(leaseMS/3)*time.Millisecond),
	}, schema, policy, logger, nil
}
