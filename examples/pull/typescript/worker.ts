import pg from "pg";
import { InboxWorker, fromPg } from "@alternayte/queuebox-inbox";

// The worker stops on Ctrl+C. It stops claiming at once, and the handlers already running keep
// their grace.
const stopping = new AbortController();
process.on("SIGINT", () => stopping.abort());
process.on("SIGTERM", () => stopping.abort());

const pool = new pg.Pool({
  connectionString: process.env.QUEUEBOX_DB
    ?? "postgres://queuebox:queuebox@localhost:5432/queuebox",
});

const worker = new InboxWorker(fromPg(pool), {
  source: "orders",
  batchSize: 10,
  leaseMs: 30_000,
  logger: {
    info: (message, fields) => console.log(message, fields ?? {}),
    warn: (message, fields) => console.warn(message, fields ?? {}),
    error: (message, fields) => console.error(message, fields ?? {}),
  },
});

console.log("The worker waits for the source 'orders'. Press Ctrl+C to stop it.");

await worker.run(async (message, tx) => {
  // Write every application change through this transaction. The library runs the completion in
  // the same transaction, so the two commit together or neither of them does.
  const payload = message.payload as { id: string; total: number };

  await tx.query("INSERT INTO orders (id, total) VALUES ($1, $2)", [payload.id, payload.total]);

  console.log(`Stored the order ${message.idempotencyKey}.`);
}, stopping.signal);

await pool.end();
