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
interface InboxQueryResult {
    readonly rows: ReadonlyArray<Record<string, unknown>>;
    readonly rowCount: number;
}
/**
 * What a handler receives. Every application write goes through it, so the write and the
 * completion commit together.
 */
interface InboxTransaction {
    /**
     * Run one statement.
     *
     * @param text the SQL, in the placeholder style of your own database
     * @param params the values, in the order the text names them
     */
    query(text: string, params?: readonly unknown[]): Promise<InboxQueryResult>;
}
/** A connection that the library owns and returns. */
interface InboxConnection extends InboxTransaction {
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
interface InboxConnectionSource {
    connect(): Promise<InboxConnection>;
}
/** The part of a `pg` pool that the adapter uses. */
interface PgPoolLike {
    connect(): Promise<PgClientLike>;
}
/** The part of a `pg` client that the adapter uses. */
interface PgClientLike {
    query(text: string, values?: readonly unknown[]): Promise<{
        rows: Array<Record<string, unknown>>;
        rowCount: number | null;
    }>;
    release(): void;
}
/**
 * Use a `pg` pool.
 *
 * @param pool the pool. The caller keeps ownership of it.
 * @returns the connection source
 */
declare function fromPg(pool: PgPoolLike): InboxConnectionSource;
/** The part of the `mssql` module that the adapter uses. */
interface MssqlModuleLike {
    Transaction: new (pool: unknown) => MssqlTransactionLike;
    Request: new (parent: unknown) => MssqlRequestLike;
}
/** The part of an `mssql` transaction that the adapter uses. */
interface MssqlTransactionLike {
    begin(): Promise<unknown>;
    commit(): Promise<unknown>;
    rollback(): Promise<unknown>;
}
/** The part of an `mssql` request that the adapter uses. */
interface MssqlRequestLike {
    input(name: string, value: unknown): MssqlRequestLike;
    query(text: string): Promise<{
        recordset?: Array<Record<string, unknown>>;
        rowsAffected: number[];
    }>;
}
/** The part of an `mssql` pool that the adapter uses. */
interface MssqlPoolLike {
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
declare function fromMssql(mssql: MssqlModuleLike, pool: MssqlPoolLike): InboxConnectionSource;

/**
 * One claimed inbox row, as the handler sees it.
 *
 * The claim token is deliberately absent. The library owns the token, because a handler that
 * could reach it could complete a message out of band.
 */
interface InboxMessage {
    /** The inbox row identifier. */
    readonly id: string;
    /** The source name. */
    readonly source: string;
    /** The deduplication key. The full identity is the source and this key together. */
    readonly idempotencyKey: string;
    /** The aggregate identifier, or null. */
    readonly aggregateId: string | null;
    /** The event type, or null. */
    readonly eventType: string | null;
    /** The JSON body, parsed. */
    readonly payload: unknown;
    /** The delivery counter. It is zero on the first delivery. */
    readonly attempt: number;
    /** The correlation identifier for logs, or null. */
    readonly correlationId: string | null;
}

/** What the library does with a message whose handler threw. */
type InboxFailureAction = {
    readonly retry: true;
    readonly delayMs: number;
} | {
    readonly retry: false;
};
/** Return the message to pending after a delay. */
declare function retryAfter(delayMs: number): InboxFailureAction;
/** Move the message to the dead letter and stop. */
declare function deadLetter(): InboxFailureAction;
/**
 * Decides what happens to a message whose handler threw.
 *
 * The library exposes the decision, because only the application knows that a validation error
 * must never be retried while a timeout must.
 */
type InboxRetryPolicy = (message: InboxMessage, failure: unknown) => InboxFailureAction;
/** The settings of the default policy. */
interface DefaultRetryPolicyOptions {
    /** The attempt ceiling. The row starts at zero, so the ceiling allows one more delivery. */
    readonly maxAttempts?: number;
    /** The delay after the first failure. */
    readonly baseDelayMs?: number;
    /** The ceiling of the backoff. */
    readonly maxDelayMs?: number;
    /** The fraction of the delay that varies, from zero to one. */
    readonly jitter?: number;
}
/**
 * Retry while `attempt < maxAttempts`, with an exponential backoff and jitter, and dead-letter
 * after that.
 *
 * @param options the settings
 * @returns the policy
 */
declare function defaultRetryPolicy(options?: DefaultRetryPolicyOptions): InboxRetryPolicy;

/** The database dialect that the inbox table lives in. */
type SqlDialect = "postgresql" | "sqlserver";
/**
 * The name of the inbox table and of every column the library reads or writes.
 * QueueBox lets an operator map these names, so the library must not assume the defaults.
 */
interface InboxSchema {
    readonly table: string;
    readonly id: string;
    readonly consumption: string;
    readonly source: string;
    readonly state: string;
    readonly scheduledAt: string;
    readonly createdAt: string;
    readonly claimToken: string;
    readonly claimedAt: string;
    readonly leaseExpiresAt: string;
    readonly processedAt: string;
    readonly attempt: string;
    readonly lastError: string;
    readonly idempotencyKey: string;
    readonly aggregateId: string;
    readonly eventType: string;
    readonly payload: string;
    readonly correlationId: string;
}
/** The QueueBox V6 names. */
declare const defaultSchema: InboxSchema;
/**
 * One statement, with the order of its parameters.
 *
 * Both dialects bind POSITIONALLY, because pg accepts no named parameter. PostgreSQL writes
 * `$1` and SQL Server writes `@p1`, and one connection interface therefore serves both drivers.
 */
interface InboxStatement {
    readonly text: string;
    /** The parameters, in the order the text names them. */
    readonly params: readonly string[];
}
/** The five statements of the pull contract, for one dialect and one schema. */
interface InboxStatements {
    /** Takes rows. Parameters: source, batch, leaseMs. */
    readonly claim: InboxStatement;
    /** Extends the lease. Parameters: leaseMs, id, token. */
    readonly renew: InboxStatement;
    /** Marks the row processed. Parameters: id, token. */
    readonly complete: InboxStatement;
    /** Returns the row to pending. Parameters: delayMs, error, id, token. */
    readonly retry: InboxStatement;
    /** Marks the row dead. Parameters: error, id, token. */
    readonly dead: InboxStatement;
}
/**
 * Render the five statements.
 *
 * @param dialect the database dialect
 * @param schema the table and column names
 * @returns the statements, with every identifier quoted
 */
declare function inboxSql(dialect: SqlDialect, schema: InboxSchema): InboxStatements;

/** The logger the library writes to. It prints nothing without one. */
interface InboxLogger {
    info(message: string, fields?: Record<string, unknown>): void;
    warn(message: string, fields?: Record<string, unknown>): void;
    error(message: string, fields?: Record<string, unknown>): void;
}
/** The settings of one worker. */
interface InboxOptions {
    /** The source whose messages this worker takes. It is mandatory. */
    readonly source: string;
    /** The largest number of messages one claim takes. */
    readonly batchSize?: number;
    /** The lease duration in milliseconds. The renewal runs every third of it. */
    readonly leaseMs?: number;
    /** The largest number of handlers that run at one time. It never passes the batch size. */
    readonly maxConcurrency?: number;
    /** How long the worker waits after a claim that returned nothing. */
    readonly pollIntervalMs?: number;
    /** How long a shutdown waits for the handlers that already run. */
    readonly shutdownGraceMs?: number;
    /** The database dialect. */
    readonly dialect?: SqlDialect;
    /** The table and column names. */
    readonly schema?: InboxSchema;
    /** What happens to a message whose handler threw. */
    readonly retryPolicy?: InboxRetryPolicy;
    /** The caller's logger. */
    readonly logger?: InboxLogger;
}
/** The settings after the defaults are applied. */
interface ResolvedOptions {
    readonly source: string;
    readonly batchSize: number;
    readonly leaseMs: number;
    readonly concurrency: number;
    readonly pollIntervalMs: number;
    readonly shutdownGraceMs: number;
    readonly dialect: SqlDialect;
    readonly renewalIntervalMs: number;
}
/**
 * Apply the defaults and reject a setting that cannot work.
 *
 * @param options the caller's settings
 * @returns the settings the worker applies
 */
declare function resolveOptions(options: InboxOptions): ResolvedOptions;

/**
 * Handles one message.
 *
 * The transaction is the point of the pull path. Write every application change through it. The
 * library runs the completion in the same transaction after the handler returns, so the writes
 * and the completion commit together or neither of them does.
 *
 * Do not commit and do not roll back. The library owns both.
 *
 * The signal aborts when the lease is lost and when a shutdown runs out of grace.
 */
type InboxHandler = (message: InboxMessage, transaction: InboxTransaction, signal: AbortSignal) => Promise<void>;
/**
 * Claims QueueBox pull messages, hands each one to a handler inside a transaction, and
 * completes, retries or dead-letters it.
 */
declare class InboxWorker {
    #private;
    /**
     * @param connections opens the connections the worker needs
     * @param options the settings
     */
    constructor(connections: InboxConnectionSource, options: InboxOptions);
    /**
     * Run until the signal aborts.
     *
     * An abort stops the claiming at once. The handlers that already run keep their grace, and a
     * handler that does not finish inside it is aborted and its message is abandoned. An abandoned
     * message is never completed; its lease expires and another worker takes it.
     *
     * @param handler handles one message
     * @param signal stops the worker
     */
    run(handler: InboxHandler, signal?: AbortSignal): Promise<void>;
}

/**
 * Prepares an error text for the `last_error` column and for a log line.
 * This is a port of `ErrorSanitizer` and `CredentialMasking` in QueueBox. The three must not
 * drift apart.
 */
/** The longest text that the sanitiser returns. */
declare const MAX_ERROR_LENGTH = 2000;
/**
 * Redact every secret value in the text, then truncate the result.
 *
 * @param text the text, which can be undefined
 * @returns the safe text, or undefined when the input was undefined
 */
declare function sanitize(text: string | undefined): string | undefined;
/**
 * Name the failure and every cause below it, then redact the whole text.
 *
 * A driver puts the connection string in the message of the cause, not of the wrapper. The chain
 * therefore reaches the redaction, and no cause message escapes it.
 *
 * @param failure the thrown value, which is not always an Error
 * @returns the safe text
 */
declare function sanitizeError(failure: unknown): string | undefined;

export { type DefaultRetryPolicyOptions, type InboxConnection, type InboxConnectionSource, type InboxFailureAction, type InboxHandler, type InboxLogger, type InboxMessage, type InboxOptions, type InboxQueryResult, type InboxRetryPolicy, type InboxSchema, type InboxStatement, type InboxStatements, type InboxTransaction, InboxWorker, MAX_ERROR_LENGTH, type MssqlModuleLike, type MssqlPoolLike, type PgClientLike, type PgPoolLike, type SqlDialect, deadLetter, defaultRetryPolicy, defaultSchema, fromMssql, fromPg, inboxSql, resolveOptions, retryAfter, sanitize, sanitizeError };
