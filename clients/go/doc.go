// Package queuebox is a pull-inbox worker for QueueBox.
//
// QueueBox makes the push path need no code. The pull path needed five SQL statements, a renewal
// timer and a lease discipline, written by hand in every application. This package holds all of
// it.
//
// # The guarantee
//
// The application's writes and the completion commit together. The handler receives the
// transaction, and the library runs the completion inside it. If the completion affects no row,
// the lease was lost, another worker owns the message, and the library rolls the writes back and
// reports nothing as done.
//
// # Use
//
//	worker, err := queuebox.NewInboxWorker(db, queuebox.Options{Source: "orders"})
//	if err != nil {
//		return err
//	}
//
//	err = worker.Run(ctx, func(ctx context.Context, message queuebox.Message, tx *sql.Tx) error {
//		_, err := tx.ExecContext(ctx, "INSERT INTO orders (id) VALUES ($1)", message.IdempotencyKey)
//		return err
//	})
//
// Write every application change through the transaction the handler receives. Do not commit and
// do not roll back, because the library owns both. Return an error to fail the message. Honour
// the context: it is cancelled when the lease is lost and when a shutdown runs out of grace.
//
// # Databases
//
// PostgreSQL through pgx, and SQL Server through go-mssqldb. The library talks to database/sql
// and imports no driver, so the application registers the one it already uses. Both dialects
// bind positionally: PostgreSQL writes $1 and SQL Server writes @p1.
//
// See the README for the settings, the retry policy, the shutdown behaviour and the schema
// mapping.
package queuebox
