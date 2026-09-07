# queuebox-go

A pull-inbox worker for [QueueBox](https://github.com/alternayte/queuebox).

QueueBox makes the push path need no code. The pull path needed five SQL statements, a renewal
timer and a lease discipline, written by hand in every application. This package holds all of it.

## What it guarantees

**Your writes and the completion commit together.** That is the whole point of the pull path.
The handler receives the transaction, and the library runs the completion inside it. If the
completion affects no row, the lease was lost, another worker owns the message, and the library
rolls your writes back and reports nothing as done.

## Requirements

| | |
|---|---|
| Go | 1.24 or later |
| Database | PostgreSQL through `pgx`, or SQL Server through `go-mssqldb` |
| QueueBox | 0.1.0 or later, which is the V6 schema |

The library talks to `database/sql`, so it needs no driver of its own. Your application registers
the driver it already uses.

## Install

```
go get github.com/alternayte/queuebox/clients/go
```

The import path ends in `go` and the package is named `queuebox`:

```go
import queuebox "github.com/alternayte/queuebox/clients/go"
```

## PostgreSQL

```go
package main

import (
	"context"
	"database/sql"
	"log"
	"os"
	"os/signal"
	"syscall"

	_ "github.com/jackc/pgx/v5/stdlib"
	queuebox "github.com/alternayte/queuebox/clients/go"
)

func main() {
	db, err := sql.Open("pgx", os.Getenv("QUEUEBOX_DB"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	worker, err := queuebox.NewInboxWorker(db, queuebox.Options{
		Source: "orders", BatchSize: 10, LeaseMS: 30_000,
	})
	if err != nil {
		log.Fatal(err)
	}

	// The worker stops on Ctrl+C. It stops claiming at once, and the handlers already running
	// keep their grace.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	err = worker.Run(ctx, func(ctx context.Context, message queuebox.Message, tx *sql.Tx) error {
		var order struct {
			ID    string `json:"id"`
			Total int    `json:"total"`
		}

		if err := message.UnmarshalPayload(&order); err != nil {
			return err
		}

		_, err := tx.ExecContext(ctx, "INSERT INTO orders (id, total) VALUES ($1, $2)", order.ID, order.Total)

		return err
	})
	if err != nil {
		log.Fatal(err)
	}
}
```

## SQL Server

Register `go-mssqldb`, open the pool with `sql.Open("sqlserver", ...)`, and set the dialect.
Everything else is the same.

```go
worker, err := queuebox.NewInboxWorker(db, queuebox.Options{
	Source:  "orders",
	Dialect: queuebox.DialectSQLServer,
})
```

## Placeholders

Both dialects bind **positionally**, because the PostgreSQL driver accepts no named parameter.
Write the placeholder style of your own database: `$1` for PostgreSQL, `@p1` for SQL Server.

## What the handler receives

| Field | Meaning |
|-------|---------|
| `message.ID` | The inbox row identifier |
| `message.Source` | The source name |
| `message.IdempotencyKey` | The deduplication key. The full identity is the source and this key together |
| `message.AggregateID` | Nullable |
| `message.EventType` | Nullable |
| `message.Payload` | The JSON body. `UnmarshalPayload` parses it |
| `message.Attempt` | Zero on the first delivery |
| `message.CorrelationID` | Nullable, for logs |

The claim token is absent on purpose. The library owns the token, because a handler that could
reach it could complete a message out of band.

## Rules for a handler

1. Write every application change through the transaction the handler receives.
2. Do not commit and do not roll back. The library owns both.
3. Return an error to fail the message. The library rolls the transaction back, so no partial
   write stays.
4. Honour the context. It is cancelled when the lease is lost and when a shutdown runs out of
   grace.
5. Do external work, such as an HTTP call, only where a repeat is safe. A transaction cannot roll
   back a call to another system. Deduplicate on the source and the idempotency key.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `Source` | none, it is mandatory | The source whose messages this worker takes |
| `BatchSize` | 10 | The largest number of messages one claim takes |
| `LeaseMS` | 30000 | The lease duration. The renewal runs every third of it |
| `MaxConcurrency` | the batch size | The largest number of handlers that run at one time |
| `PollInterval` | 1 second | The wait after a claim that returned nothing |
| `ShutdownGrace` | 30 seconds | How long a stop waits for the handlers already running |
| `Dialect` | `DialectPostgreSQL` | The database dialect |
| `Schema` | the QueueBox names | The table and column names, when an operator mapped them |
| `RetryPolicy` | five attempts, exponential | What happens to a message whose handler failed |
| `Logger` | none | The caller's logger. The library prints nothing without one |

## Failure, retry and the dead letter

The default policy retries while the attempt is below the ceiling, with an exponential backoff
and jitter, and dead-letters after that. Only your application knows that a validation error must
never be retried while a timeout must, so write your own:

```go
policy := queuebox.RetryPolicyFunc(func(message queuebox.Message, failure error) queuebox.FailureAction {
	// A bad payload never becomes good. Do not spend five attempts on it.
	var syntax *json.SyntaxError
	if errors.As(failure, &syntax) {
		return queuebox.DeadLetter()
	}

	if message.Attempt >= 5 {
		return queuebox.DeadLetter()
	}

	return queuebox.RetryAfter(time.Duration(1<<message.Attempt) * time.Second)
})
```

## Shutdown

Cancel the context you passed to `Run`. The worker stops claiming at once. The handlers that
already run keep `ShutdownGrace`, and a handler that does not finish inside it is cancelled. Its
message is abandoned: the library completes nothing, spends no attempt on it, and lets the lease
expire so another worker takes it.

## Logging

Set `Logger`. Without one the library prints nothing. Every message and every error passes
through the same redaction QueueBox itself uses, so a connection string password reaches neither
a log line nor the `last_error` column.

## Mapped table and column names

QueueBox lets an operator rename the inbox table and its columns. Name them here as well:

```go
schema := queuebox.DefaultSchema()
schema.Table = "qb_messages"
schema.State = "row_state"

worker, err := queuebox.NewInboxWorker(db, queuebox.Options{Source: "orders", Schema: &schema})
```

Every name is quoted and checked before it reaches the database, so a mapping cannot carry SQL.

## Tests

```bash
go test ./...            # the unit tests and the shared redaction corpus
cd contract && go test ./...   # the contract, against real databases in containers
```

The contract tests live in their own module. They need Testcontainers and both database drivers,
and those require a newer Go than this library does. A test dependency must never raise the
floor of a published library, so the heavy dependencies stay out of the module you install.

## Releases

Go ties the tag of a module in a subdirectory to that subdirectory, so the tag carries the path:

```bash
git tag clients/go/v0.1.1 && git push origin clients/go/v0.1.1
```

## License

Apache-2.0.
