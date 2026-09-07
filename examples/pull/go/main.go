// Command pullworker is the README example of the QueueBox Go client library, as a program that
// runs.
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

type consoleLogger struct{}

func (consoleLogger) Info(message string, fields map[string]any)  { log.Println("INFO", message, fields) }
func (consoleLogger) Warn(message string, fields map[string]any)  { log.Println("WARN", message, fields) }
func (consoleLogger) Error(message string, fields map[string]any) { log.Println("ERROR", message, fields) }

func main() {
	dsn := os.Getenv("QUEUEBOX_DB")
	if dsn == "" {
		dsn = "postgres://queuebox:queuebox@localhost:5432/queuebox?sslmode=disable"
	}

	db, err := sql.Open("pgx", dsn)
	if err != nil {
		log.Fatal(err)
	}

	defer func() { _ = db.Close() }()

	worker, err := queuebox.NewInboxWorker(db, queuebox.Options{
		Source:    "orders",
		BatchSize: 10,
		LeaseMS:   30_000,
		Logger:    consoleLogger{},
	})
	if err != nil {
		log.Fatal(err)
	}

	// The worker stops on Ctrl+C. It stops claiming at once, and the handlers already running
	// keep their grace.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	log.Println("The worker waits for the source 'orders'. Press Ctrl+C to stop it.")

	err = worker.Run(ctx, func(ctx context.Context, message queuebox.Message, tx *sql.Tx) error {
		// Write every application change through this transaction. The library runs the
		// completion in the same transaction, so the two commit together or neither of them does.
		var order struct {
			ID    string `json:"id"`
			Total int    `json:"total"`
		}

		if err := message.UnmarshalPayload(&order); err != nil {
			return err
		}

		if _, err := tx.ExecContext(ctx, "INSERT INTO orders (id, total) VALUES ($1, $2)", order.ID, order.Total); err != nil {
			return err
		}

		log.Printf("Stored the order %s.", message.IdempotencyKey)

		return nil
	})
	if err != nil {
		log.Fatal(err)
	}
}
