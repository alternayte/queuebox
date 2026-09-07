package queuebox

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// Handler handles one message.
//
// The transaction is the point of the pull path. Write every application change through it. The
// library runs the completion in the same transaction after the handler returns, so the writes
// and the completion commit together or neither of them does.
//
// Do not commit and do not roll back. The library owns both.
//
// The context is cancelled when the lease is lost and when a shutdown runs out of grace.
type Handler func(ctx context.Context, message Message, tx *sql.Tx) error

type claimedMessage struct {
	message Message
	token   string
}

// InboxWorker claims QueueBox pull messages, hands each one to a handler inside a transaction,
// and completes, retries or dead-letters it.
type InboxWorker struct {
	db         *sql.DB
	options    resolvedOptions
	schema     Schema
	statements Statements
	policy     RetryPolicy
	logger     Logger
}

// NewInboxWorker builds a worker.
//
// database/sql is the abstraction, so the library needs no driver of its own and one worker
// serves the PostgreSQL and the SQL Server drivers alike.
func NewInboxWorker(db *sql.DB, options Options) (*InboxWorker, error) {
	if db == nil {
		return nil, errors.New("the database is mandatory")
	}

	resolved, schema, policy, logger, err := options.resolve()
	if err != nil {
		return nil, err
	}

	dialect := options.Dialect
	if dialect == "" {
		dialect = DialectPostgreSQL
	}

	rendered, err := NewStatements(dialect, schema)
	if err != nil {
		return nil, err
	}

	return &InboxWorker{
		db:         db,
		options:    resolved,
		schema:     schema,
		statements: rendered,
		policy:     policy,
		logger:     logger,
	}, nil
}

// Run works until the context is cancelled.
//
// A cancellation stops the claiming at once. The handlers that already run keep their grace, and
// a handler that does not finish inside it is cancelled and its message is abandoned. An
// abandoned message is never completed; its lease expires and another worker takes it.
func (w *InboxWorker) Run(ctx context.Context, handler Handler) error {
	if handler == nil {
		return errors.New("the handler is mandatory")
	}

	// The handlers do not share the caller's context. A shutdown must let them finish, so the
	// cancellation reaches them only after the grace has run out.
	handlerCtx, stopHandlers := context.WithCancel(context.WithoutCancel(ctx))
	defer stopHandlers()

	grace := time.AfterFunc(timerNeverFires, stopHandlers)
	defer grace.Stop()

	context.AfterFunc(ctx, func() { grace.Reset(w.options.shutdownGrace) })

	for ctx.Err() == nil {
		batch, err := w.claim(ctx)
		if err != nil {
			if ctx.Err() != nil {
				break
			}

			w.logger.Error("The claim failed.", map[string]any{"error": SanitizeError(err)})
			w.wait(ctx, w.options.pollInterval)

			continue
		}

		if len(batch) == 0 {
			// No polling storm. The caller owns the interval.
			w.wait(ctx, w.options.pollInterval)
			continue
		}

		w.runBatch(handlerCtx, batch, handler)
	}

	return nil
}

// timerNeverFires is the longest duration, so the grace timer waits until the shutdown resets it.
const timerNeverFires = time.Duration(1<<63 - 1)

func (w *InboxWorker) runBatch(ctx context.Context, batch []claimedMessage, handler Handler) {
	queue := make(chan claimedMessage, len(batch))
	for _, claimed := range batch {
		queue <- claimed
	}
	close(queue)

	var group sync.WaitGroup

	for range min(w.options.concurrency, len(batch)) {
		group.Add(1)

		go func() {
			defer group.Done()

			for claimed := range queue {
				if err := w.process(ctx, claimed, handler); err != nil {
					// A failure of the library itself, not of the handler. The message keeps its
					// lease and returns when the lease expires.
					w.logger.Error("The message was abandoned.", map[string]any{
						"messageId": claimed.message.ID,
						"error":     SanitizeError(err),
					})
				}
			}
		}()
	}

	group.Wait()
}

func (w *InboxWorker) process(ctx context.Context, claimed claimedMessage, handler Handler) error {
	// The completion must run whatever the caller's context does, so the transaction does not
	// take a context that a shutdown cancels.
	tx, err := w.db.BeginTx(context.WithoutCancel(ctx), nil)
	if err != nil {
		return fmt.Errorf("the transaction did not begin: %w", err)
	}

	lease := w.startRenewal(ctx, claimed)
	defer lease.stop()

	handlerErr := handler(lease.ctx, claimed.message, tx)

	lease.stop()

	if handlerErr != nil {
		if rollbackErr := tx.Rollback(); rollbackErr != nil && !errors.Is(rollbackErr, sql.ErrTxDone) {
			return fmt.Errorf("the rollback failed: %w", rollbackErr)
		}

		if lease.lost() {
			w.logger.Warn("The lease was lost, so nothing was written.", map[string]any{"messageId": claimed.message.ID})
			return nil
		}

		if ctx.Err() != nil {
			// The shutdown ran out of grace. The message is abandoned, not failed, so it spends
			// no attempt and reaches no dead letter. Its lease expires and another worker takes
			// it.
			w.logger.Info("The shutdown abandoned the message, so it spends no attempt.",
				map[string]any{"messageId": claimed.message.ID})

			return nil
		}

		return w.applyFailure(claimed, handlerErr)
	}

	if lease.lost() {
		// The renewal already told us that another worker owns the message. The application's
		// writes must go first.
		if rollbackErr := tx.Rollback(); rollbackErr != nil && !errors.Is(rollbackErr, sql.ErrTxDone) {
			return fmt.Errorf("the rollback failed: %w", rollbackErr)
		}

		w.logger.Warn("The lease was lost, so the work was rolled back.", map[string]any{"messageId": claimed.message.ID})

		return nil
	}

	// Section 2. The completion runs inside the handler's transaction, so the application's
	// writes and the completion commit together or neither of them does.
	result, err := tx.Exec(w.statements.Complete, claimed.message.ID, claimed.token)
	if err != nil {
		_ = tx.Rollback()
		return fmt.Errorf("the completion failed: %w", err)
	}

	affected, err := result.RowsAffected()
	if err != nil {
		_ = tx.Rollback()
		return fmt.Errorf("the completion count is unknown: %w", err)
	}

	if affected == 1 {
		return tx.Commit()
	}

	if rollbackErr := tx.Rollback(); rollbackErr != nil && !errors.Is(rollbackErr, sql.ErrTxDone) {
		return fmt.Errorf("the rollback failed: %w", rollbackErr)
	}

	w.logger.Warn("The completion affected no row, so the work was rolled back.",
		map[string]any{"messageId": claimed.message.ID})

	return nil
}

func (w *InboxWorker) applyFailure(claimed claimedMessage, failure error) error {
	action := w.policy.Decide(claimed.message, failure)
	text := SanitizeError(failure)

	var (
		result sql.Result
		err    error
	)

	if action.Retry {
		delayMS := action.Delay.Milliseconds()
		result, err = w.db.Exec(w.statements.Retry, delayMS, text, claimed.message.ID, claimed.token)
	} else {
		result, err = w.db.Exec(w.statements.Dead, text, claimed.message.ID, claimed.token)
	}

	if err != nil {
		return fmt.Errorf("the failure was not recorded: %w", err)
	}

	affected, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("the failure count is unknown: %w", err)
	}

	switch {
	case affected == 0:
		// The claim was already lost. Nothing changed, which is correct.
		w.logger.Warn("The failure changed nothing, because the claim was lost.",
			map[string]any{"messageId": claimed.message.ID})
	case action.Retry:
		w.logger.Warn("The message retries.", map[string]any{"messageId": claimed.message.ID, "delay": action.Delay})
	default:
		w.logger.Error("The message reached the dead letter.", map[string]any{"messageId": claimed.message.ID})
	}

	return nil
}

func (w *InboxWorker) claim(ctx context.Context) ([]claimedMessage, error) {
	// The claim runs in a short transaction of its own and commits before any handler starts.
	tx, err := w.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}

	defer func() { _ = tx.Rollback() }()

	rows, err := tx.QueryContext(ctx, w.statements.Claim, w.options.source, w.options.batchSize, w.options.leaseMS)
	if err != nil {
		return nil, err
	}

	claimed, err := w.read(rows)
	if closeErr := rows.Close(); err == nil && closeErr != nil {
		err = closeErr
	}

	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}

	return claimed, nil
}

func (w *InboxWorker) read(rows *sql.Rows) ([]claimedMessage, error) {
	columns, err := rows.Columns()
	if err != nil {
		return nil, err
	}

	var claimed []claimedMessage

	for rows.Next() {
		holders := make([]any, len(columns))
		for i := range holders {
			holders[i] = new(any)
		}

		if err := rows.Scan(holders...); err != nil {
			return nil, err
		}

		row := make(map[string]any, len(columns))
		for i, name := range columns {
			row[name] = *(holders[i].(*any))
		}

		one, err := w.toMessage(row)
		if err != nil {
			return nil, err
		}

		claimed = append(claimed, one)
	}

	return claimed, rows.Err()
}

func (w *InboxWorker) toMessage(row map[string]any) (claimedMessage, error) {
	payload, err := asJSON(row[w.schema.Payload])
	if err != nil {
		return claimedMessage{}, err
	}

	attempt, err := asInt(row[w.schema.Attempt])
	if err != nil {
		return claimedMessage{}, err
	}

	return claimedMessage{
		message: Message{
			ID:             asUUID(row[w.schema.ID]),
			Source:         asString(row[w.schema.Source]),
			IdempotencyKey: asString(row[w.schema.IdempotencyKey]),
			AggregateID:    asNullableString(row[w.schema.AggregateID]),
			EventType:      asNullableString(row[w.schema.EventType]),
			Payload:        payload,
			Attempt:        attempt,
			CorrelationID:  asNullableString(row[w.schema.CorrelationID]),
		},
		token: asUUID(row[w.schema.ClaimToken]),
	}, nil
}

func (w *InboxWorker) wait(ctx context.Context, duration time.Duration) {
	timer := time.NewTimer(duration)
	defer timer.Stop()

	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}

// leaseRenewal renews the lease while the handler runs. A renewal that affects zero rows means
// the ownership is lost, so the renewal cancels the handler and reports the loss.
type leaseRenewal struct {
	ctx      context.Context
	cancel   context.CancelFunc
	stopOnce sync.Once
	done     chan struct{}
	stopping chan struct{}
	lostFlag atomic.Bool
}

func (l *leaseRenewal) lost() bool { return l.lostFlag.Load() }

// stop ends the renewal. The timer stops when the handler returns, however it returns.
func (l *leaseRenewal) stop() {
	l.stopOnce.Do(func() {
		close(l.stopping)
		<-l.done
	})
}

func (w *InboxWorker) startRenewal(ctx context.Context, claimed claimedMessage) *leaseRenewal {
	handlerCtx, cancel := context.WithCancel(ctx)

	lease := &leaseRenewal{
		ctx:      handlerCtx,
		cancel:   cancel,
		done:     make(chan struct{}),
		stopping: make(chan struct{}),
	}

	go func() {
		defer close(lease.done)

		ticker := time.NewTicker(w.options.renewalInterval)
		defer ticker.Stop()

		for {
			select {
			case <-lease.stopping:
				return
			case <-ticker.C:
			}

			// The renewal needs its own connection. database/sql takes one from the pool, and it
			// is never the connection that the handler's transaction holds, so the renewal
			// commits on its own.
			result, err := w.db.Exec(w.statements.Renew, w.options.leaseMS, claimed.message.ID, claimed.token)
			if err != nil {
				// A renewal that could not run is not a lost lease. The next one tries again, and
				// the lease expires on its own if none of them succeeds.
				w.logger.Warn("The renewal failed.", map[string]any{
					"messageId": claimed.message.ID,
					"error":     SanitizeError(err),
				})

				continue
			}

			affected, err := result.RowsAffected()
			if err != nil || affected == 0 {
				lease.lostFlag.Store(true)
				w.logger.Warn("The lease was lost to another worker.", map[string]any{"messageId": claimed.message.ID})
				lease.cancel()

				return
			}
		}
	}()

	return lease
}

func asString(value any) string {
	switch typed := value.(type) {
	case nil:
		return ""
	case string:
		return typed
	case []byte:
		return string(typed)
	default:
		return fmt.Sprint(typed)
	}
}

// asUUID renders an identifier as canonical text.
//
// The two drivers disagree. pgx hands a uuid back as a string. go-mssqldb hands a
// UNIQUEIDENTIFIER back as sixteen raw bytes, and SQL Server stores the first three groups of
// those bytes in little-endian order. A library that read them as text would carry binary into
// every later statement, so the conversion belongs here rather than in the application.
func asUUID(value any) string {
	raw, ok := value.([]byte)
	if !ok || len(raw) != uuidByteLength {
		return asString(value)
	}

	ordered := [uuidByteLength]byte{
		raw[3], raw[2], raw[1], raw[0],
		raw[5], raw[4],
		raw[7], raw[6],
	}
	copy(ordered[8:], raw[8:])

	return fmt.Sprintf("%x-%x-%x-%x-%x",
		ordered[0:4], ordered[4:6], ordered[6:8], ordered[8:10], ordered[10:16])
}

const uuidByteLength = 16

func asNullableString(value any) *string {
	if value == nil {
		return nil
	}

	text := asString(value)

	return &text
}

func asInt(value any) (int, error) {
	switch typed := value.(type) {
	case nil:
		return 0, nil
	case int64:
		return int(typed), nil
	case int32:
		return int(typed), nil
	case int:
		return typed, nil
	default:
		return 0, fmt.Errorf("the attempt column holds %T, which is not a whole number", value)
	}
}

// asJSON returns the payload as it was stored. PostgreSQL hands back the bytes of a jsonb column
// and SQL Server hands back the text of an nvarchar one.
func asJSON(value any) (json.RawMessage, error) {
	switch typed := value.(type) {
	case nil:
		return json.RawMessage("null"), nil
	case []byte:
		return json.RawMessage(typed), nil
	case string:
		return json.RawMessage(typed), nil
	default:
		return nil, fmt.Errorf("the payload column holds %T, which is not JSON", value)
	}
}
