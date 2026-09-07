/**
 * The connection abstraction.
 *
 * TypeScript has no standard database interface, and `pg` and `mssql` agree on nothing: not the
 * parameter style, not the transaction object, not the shape of a result. The library therefore
 * states the small interface it needs, and ships an adapter for each driver. The core imports
 * neither driver, so one package serves both dialects and the application brings the driver it
 * already has.
 */

/** The rows and the affected count of one statement. */
export interface InboxQueryResult {
  readonly rows: ReadonlyArray<Record<string, unknown>>;
  readonly rowCount: number;
}

/**
 * What a handler receives. Every application write goes through it, so the write and the
 * completion commit together.
 */
export interface InboxTransaction {
  /**
   * Run one statement.
   *
   * @param text the SQL, in the placeholder style of your own database
   * @param params the values, in the order the text names them
   */
  query(text: string, params?: readonly unknown[]): Promise<InboxQueryResult>;
}

/** A connection that the library owns and returns. */
export interface InboxConnection extends InboxTransaction {
  begin(): Promise<void>;
  commit(): Promise<void>;
  rollback(): Promise<void>;
  release(): Promise<void>;
}

/**
 * Opens a connection for the worker.
 *
 * The worker needs more than one connection at a time. The handler's transaction holds one, and
 * the lease renewal holds another, because a renewal that ran inside the handler's transaction
 * would commit with it and would therefore renew nothing.
 */
export interface InboxConnectionSource {
  connect(): Promise<InboxConnection>;
}

/** The part of a `pg` pool that the adapter uses. */
export interface PgPoolLike {
  connect(): Promise<PgClientLike>;
}

/** The part of a `pg` client that the adapter uses. */
export interface PgClientLike {
  query(text: string, values?: readonly unknown[]): Promise<{ rows: Array<Record<string, unknown>>; rowCount: number | null }>;
  release(): void;
}

/**
 * Use a `pg` pool.
 *
 * @param pool the pool. The caller keeps ownership of it.
 * @returns the connection source
 */
export function fromPg(pool: PgPoolLike): InboxConnectionSource {
  return {
    async connect(): Promise<InboxConnection> {
      const client = await pool.connect();

      return {
        async query(text, params) {
          const result = await client.query(text, params);
          return { rows: result.rows, rowCount: result.rowCount ?? 0 };
        },
        async begin() {
          await client.query("BEGIN");
        },
        async commit() {
          await client.query("COMMIT");
        },
        async rollback() {
          await client.query("ROLLBACK");
        },
        async release() {
          client.release();
        },
      };
    },
  };
}

/** The part of the `mssql` module that the adapter uses. */
export interface MssqlModuleLike {
  Transaction: new (pool: unknown) => MssqlTransactionLike;
  Request: new (parent: unknown) => MssqlRequestLike;
}

/** The part of an `mssql` transaction that the adapter uses. */
export interface MssqlTransactionLike {
  begin(): Promise<unknown>;
  commit(): Promise<unknown>;
  rollback(): Promise<unknown>;
}

/** The part of an `mssql` request that the adapter uses. */
export interface MssqlRequestLike {
  input(name: string, value: unknown): MssqlRequestLike;
  query(text: string): Promise<{ recordset?: Array<Record<string, unknown>>; rowsAffected: number[] }>;
}

/** The part of an `mssql` pool that the adapter uses. */
export interface MssqlPoolLike {
  request(): MssqlRequestLike;
}

/**
 * Use an `mssql` connection pool.
 *
 * The module itself is a parameter, because the adapter needs the `Transaction` and `Request`
 * constructors and the core package imports no driver.
 *
 * @param mssql the `mssql` module
 * @param pool the connected pool. The caller keeps ownership of it.
 * @returns the connection source
 */
export function fromMssql(mssql: MssqlModuleLike, pool: MssqlPoolLike): InboxConnectionSource {
  return {
    async connect(): Promise<InboxConnection> {
      let transaction: MssqlTransactionLike | undefined;

      const run = async (text: string, params: readonly unknown[] = []): Promise<InboxQueryResult> => {
        const request = transaction === undefined ? pool.request() : new mssql.Request(transaction);

        // Both dialects bind positionally. SQL Server writes `@p1`, so the values map onto
        // `p1` and upwards, in order.
        params.forEach((value, index) => {
          request.input(`p${index + 1}`, value);
        });

        const result = await request.query(text);

        return { rows: result.recordset ?? [], rowCount: result.rowsAffected[0] ?? 0 };
      };

      return {
        query: run,
        async begin() {
          const started = new mssql.Transaction(pool);
          await started.begin();
          transaction = started;
        },
        async commit() {
          await transaction?.commit();
          transaction = undefined;
        },
        async rollback() {
          await transaction?.rollback();
          transaction = undefined;
        },
        async release() {
          // The pool owns the connection. A transaction that is still open must not leak.
          if (transaction !== undefined) {
            await transaction.rollback().catch(() => undefined);
            transaction = undefined;
          }
        },
      };
    },
  };
}
