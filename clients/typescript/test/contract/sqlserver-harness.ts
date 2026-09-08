import mssql from "mssql";
import { MSSQLServerContainer } from "@testcontainers/mssqlserver";
import type { StartedMSSQLServerContainer } from "@testcontainers/mssqlserver";

import { defaultSchema, fromMssql } from "../../src/index.ts";
import type { InboxConnectionSource, InboxSchema, SqlDialect } from "../../src/index.ts";
import { mappedSchema, migrations } from "./harness.ts";
import type { DatabaseHarness, PendingRow } from "./harness.ts";

/** A real SQL Server, in a container, with the QueueBox migrations applied. */
export class SqlServerHarness implements DatabaseHarness {
  readonly dialect: SqlDialect = "sqlserver";
  readonly insertApplicationRow = "INSERT INTO app_orders ([key]) VALUES (@p1)";

  #container?: StartedMSSQLServerContainer;
  #pool?: mssql.ConnectionPool;

  get connections(): InboxConnectionSource {
    return fromMssql(mssql as never, this.#requirePool());
  }

  /** The raw pool, for a test that must hold a lock the worker itself does not take. */
  get rawPool(): mssql.ConnectionPool {
    return this.#requirePool();
  }

  async start(): Promise<void> {
    this.#container = await new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense().start();
    // The SQL Server claim carries its own 30 second sp_getapplock lock timeout (see
    // examples/pull/sql/sqlserver/claim.sql). The driver's own default request timeout is 15
    // seconds, shorter than that, so a caller must raise it past 30 seconds or the driver cancels
    // the claim on its own timeout before the claim's THROW 51000 ever has a chance to run.
    this.#pool = await new mssql.ConnectionPool(`${this.#container.getConnectionUri()};Request Timeout=45000`).connect();

    for (const script of migrations("sqlserver")) {
      await this.#run(script);
    }

    await this.#run("CREATE TABLE app_orders ([key] NVARCHAR(255) PRIMARY KEY)");
  }

  async stop(): Promise<void> {
    await this.#pool?.close();
    await this.#container?.stop();
  }

  async reset(): Promise<void> {
    await this.#run("DELETE FROM inbox");
    await this.#run("DELETE FROM app_orders");
  }

  async insertPending(row: PendingRow): Promise<string> {
    const result = await this.#requirePool().request()
      .input("source", row.source)
      .input("key", row.idempotencyKey)
      .input("aggregate", row.aggregateId ?? null)
      .input("type", row.eventType ?? null)
      .input("payload", row.payload)
      .input("attempt", row.attempt ?? 0)
      .input("correlation", row.correlationId ?? null)
      .query(`INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
                                 consumption, scheduled_at, attempt, correlation_id)
              OUTPUT INSERTED.id
              VALUES (@source, @key, @aggregate, @type, @payload, 'pending', 'pull',
                      SYSUTCDATETIME(), @attempt, @correlation)`);

    return String(result.recordset[0].id);
  }

  async readRow(id: string): Promise<{ state: string; attempt: number; lastError: string | null }> {
    const result = await this.#requirePool().request().input("id", id)
      .query("SELECT state, attempt, last_error FROM inbox WHERE id = @id");
    const row = result.recordset[0];

    return { state: String(row.state), attempt: Number(row.attempt), lastError: row.last_error ?? null };
  }

  async readScheduledAt(id: string): Promise<Date> {
    const result = await this.#requirePool().request().input("id", id)
      .query("SELECT scheduled_at FROM inbox WHERE id = @id");

    // The column is DATETIME2 and it holds UTC.
    return new Date(`${new Date(result.recordset[0].scheduled_at).toISOString().replace("Z", "")}Z`);
  }

  async stealClaim(id: string): Promise<void> {
    await this.#requirePool().request().input("id", id).query(
      `UPDATE inbox SET state = 'processing', claim_token = NEWID(),
                        lease_expires_at = DATEADD(minute, 10, SYSUTCDATETIME())
       WHERE id = @id`);
  }

  async countApplicationRows(key: string): Promise<number> {
    const result = await this.#requirePool().request().input("key", key)
      .query("SELECT COUNT(*) AS n FROM app_orders WHERE [key] = @key");

    return Number(result.recordset[0].n);
  }

  async createMappedSchema(): Promise<InboxSchema> {
    const schema = mappedSchema(defaultSchema);

    await this.#run("IF OBJECT_ID('qb_messages', 'U') IS NOT NULL DROP TABLE qb_messages");
    await this.#run(`
      CREATE TABLE qb_messages (
        message_id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
        channel NVARCHAR(255) NOT NULL,
        dedup_key NVARCHAR(255) NOT NULL,
        aggregate_id NVARCHAR(255),
        event_type NVARCHAR(255),
        body NVARCHAR(MAX) NOT NULL,
        row_state NVARCHAR(50) NOT NULL DEFAULT 'pending',
        created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        processed_at DATETIME2,
        correlation_id NVARCHAR(128),
        lease_token UNIQUEIDENTIFIER,
        claimed_at DATETIME2,
        lease_expires_at DATETIME2,
        consumption NVARCHAR(4) NOT NULL DEFAULT 'push',
        scheduled_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        tries INT NOT NULL DEFAULT 0,
        last_error NVARCHAR(MAX)
      )`);

    return schema;
  }

  async insertMappedPending(schema: InboxSchema, source: string, key: string, payload: string): Promise<string> {
    const result = await this.#requirePool().request()
      .input("source", source).input("key", key).input("payload", payload)
      .query(`INSERT INTO ${schema.table} (${schema.source}, ${schema.idempotencyKey}, ${schema.payload}, ${schema.state}, ${schema.consumption})
              OUTPUT INSERTED.${schema.id}
              VALUES (@source, @key, @payload, 'pending', 'pull')`);

    return String(result.recordset[0][schema.id]);
  }

  async readMappedState(schema: InboxSchema, id: string): Promise<string> {
    const result = await this.#requirePool().request().input("id", id)
      .query(`SELECT ${schema.state} AS s FROM ${schema.table} WHERE ${schema.id} = @id`);

    return String(result.recordset[0].s);
  }

  #requirePool(): mssql.ConnectionPool {
    if (this.#pool === undefined) {
      throw new Error("The harness did not start.");
    }

    return this.#pool;
  }

  async #run(sql: string): Promise<void> {
    await this.#requirePool().request().query(sql);
  }
}
