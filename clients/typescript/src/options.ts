import type { InboxRetryPolicy } from "./retry.ts";
import type { InboxSchema, SqlDialect } from "./sql.ts";

/** The logger the library writes to. It prints nothing without one. */
export interface InboxLogger {
  info(message: string, fields?: Record<string, unknown>): void;
  warn(message: string, fields?: Record<string, unknown>): void;
  error(message: string, fields?: Record<string, unknown>): void;
}

/** The settings of one worker. */
export interface InboxOptions {
  /** The source whose messages this worker takes. It is mandatory. */
  readonly source: string;
  /** The largest number of messages one claim takes. */
  readonly batchSize?: number;
  /** The lease duration in milliseconds. The renewal runs every third of it. */
  readonly leaseMs?: number;
  /**
   * The largest number of handlers that run at one time. It never passes the batch size.
   * The default is one. A handler meets no sibling message unless the caller asks for
   * parallelism.
   */
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
export interface ResolvedOptions {
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
export function resolveOptions(options: InboxOptions): ResolvedOptions {
  if (typeof options.source !== "string" || options.source.trim() === "") {
    throw new TypeError("The source is mandatory.");
  }

  const batchSize = options.batchSize ?? 10;
  const leaseMs = options.leaseMs ?? 30_000;
  const pollIntervalMs = options.pollIntervalMs ?? 1000;
  const shutdownGraceMs = options.shutdownGraceMs ?? 30_000;

  if (!Number.isInteger(batchSize) || batchSize <= 0) {
    throw new RangeError("The batchSize must be a positive whole number.");
  }

  // The renewal runs every third of the lease, so a shorter lease has no interval at all.
  if (!Number.isInteger(leaseMs) || leaseMs < 3) {
    throw new RangeError("The leaseMs must be a whole number of three or more.");
  }

  if (options.maxConcurrency !== undefined && (!Number.isInteger(options.maxConcurrency) || options.maxConcurrency <= 0)) {
    throw new RangeError("The maxConcurrency must be a positive whole number.");
  }

  if (pollIntervalMs <= 0) {
    throw new RangeError("The pollIntervalMs must be positive.");
  }

  if (shutdownGraceMs < 0) {
    throw new RangeError("The shutdownGraceMs must not be negative.");
  }

  return {
    source: options.source,
    batchSize,
    leaseMs,
    // Bounded memory: never hold more than the configured batch.
    concurrency: Math.min(options.maxConcurrency ?? 1, batchSize),
    pollIntervalMs,
    shutdownGraceMs,
    dialect: options.dialect ?? "postgresql",
    renewalIntervalMs: Math.max(1, Math.floor(leaseMs / 3)),
  };
}
