import pg from "pg";
import { PostgreSqlContainer } from "@testcontainers/postgresql";
import type { StartedPostgreSqlContainer } from "@testcontainers/postgresql";

import { defaultSchema, fromPg } from "../../src/index.ts";
import type { InboxConnectionSource, InboxSchema, SqlDialect } from "../../src/index.ts";
import { mappedSchema, migrations } from "./harness.ts";
import type { DatabaseHarness, PendingRow } from "./harness.ts";

/** A real PostgreSQL, in a container, with the QueueBox migrations applied. */
export class PostgresHarness implements DatabaseHarness {
  readonly dialect: SqlDialect = "postgresql";
  readonly insertApplicationRow = "INSERT INTO app_orders (key) VALUES ($1)";

  #container?: StartedPostgreSqlContainer;
  #pool?: pg.Pool;

  get connections(): InboxConnectionSource {
    return fromPg(this.#requirePool());
  }

  async start(): Promise<void> {
    this.#container = await new PostgreSqlContainer("postgres:16-alpine").start();
    this.#pool = new pg.Pool({ connectionString: this.#container.getConnectionUri() });

    for (const script of migrations("postgresql")) {
      await this.#run(script);
    }

    await this.#run("CREATE TABLE app_orders (key TEXT PRIMARY KEY)");
  }

  async stop(): Promise<void> {
    await this.#pool?.end();
    await this.#container?.stop();
  }

  async reset(): Promise<void> {
    await this.#run("DELETE FROM inbox");
    await this.#run("DELETE FROM app_orders");
  }

  async insertPending(row: PendingRow): Promise<string> {
    const result = await this.#requirePool().query(
      `INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
                          consumption, scheduled_at, attempt, correlation_id)
       VALUES ($1, $2, $3, $4, $5::jsonb, 'pending', 'pull', CURRENT_TIMESTAMP, $6, $7)
       RETURNING id`,
      [row.source, row.idempotencyKey, row.aggregateId ?? null, row.eventType ?? null,
        row.payload, row.attempt ?? 0, row.correlationId ?? null],
    );

    return String(result.rows[0].id);
  }

  async readRow(id: string): Promise<{ state: string; attempt: number; lastError: string | null }> {
    const result = await this.#requirePool().query("SELECT state, attempt, last_error FROM inbox WHERE id = $1", [id]);
    const row = result.rows[0];

    return { state: String(row.state), attempt: Number(row.attempt), lastError: row.last_error ?? null };
  }

  async readScheduledAt(id: string): Promise<Date> {
    const result = await this.#requirePool().query("SELECT scheduled_at FROM inbox WHERE id = $1", [id]);
    return new Date(result.rows[0].scheduled_at);
  }

  async stealClaim(id: string): Promise<void> {
    // A real second worker also takes the row into 'processing'.
    await this.#requirePool().query(
      `UPDATE inbox SET state = 'processing', claim_token = gen_random_uuid(),
                        lease_expires_at = clock_timestamp() + INTERVAL '10 minutes'
       WHERE id = $1`,
      [id],
    );
  }

  async countApplicationRows(key: string): Promise<number> {
    const result = await this.#requirePool().query("SELECT COUNT(*)::int AS n FROM app_orders WHERE key = $1", [key]);
    return Number(result.rows[0].n);
  }

  async createMappedSchema(): Promise<InboxSchema> {
    const schema = mappedSchema(defaultSchema);

    await this.#run("DROP TABLE IF EXISTS qb_messages");
    await this.#run(`
      CREATE TABLE qb_messages (
        message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        channel VARCHAR(255) NOT NULL,
        dedup_key VARCHAR(255) NOT NULL,
        aggregate_id VARCHAR(255),
        event_type VARCHAR(255),
        body JSONB NOT NULL,
        row_state VARCHAR(50) NOT NULL DEFAULT 'pending',
        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        processed_at TIMESTAMPTZ,
        correlation_id VARCHAR(128),
        lease_token UUID,
        claimed_at TIMESTAMPTZ,
        lease_expires_at TIMESTAMPTZ,
        consumption VARCHAR(4) NOT NULL DEFAULT 'push',
        scheduled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        tries INT NOT NULL DEFAULT 0,
        last_error TEXT
      )`);

    return schema;
  }

  async insertMappedPending(schema: InboxSchema, source: string, key: string, payload: string): Promise<string> {
    const result = await this.#requirePool().query(
      `INSERT INTO ${schema.table} (${schema.source}, ${schema.idempotencyKey}, ${schema.payload}, ${schema.state}, ${schema.consumption})
       VALUES ($1, $2, $3::jsonb, 'pending', 'pull') RETURNING ${schema.id}`,
      [source, key, payload],
    );

    return String(result.rows[0][schema.id]);
  }

  async readMappedState(schema: InboxSchema, id: string): Promise<string> {
    const result = await this.#requirePool().query(
      `SELECT ${schema.state} AS s FROM ${schema.table} WHERE ${schema.id} = $1`, [id]);
    return String(result.rows[0].s);
  }

  #requirePool(): pg.Pool {
    if (this.#pool === undefined) {
      throw new Error("The harness did not start.");
    }

    return this.#pool;
  }

  async #run(sql: string): Promise<void> {
    await this.#requirePool().query(sql);
  }
}
