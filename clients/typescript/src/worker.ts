import type { InboxConnection, InboxConnectionSource, InboxTransaction } from "./connections.ts";
import type { InboxLogger, InboxOptions } from "./options.ts";
import { resolveOptions } from "./options.ts";
import type { InboxMessage } from "./message.ts";
import { defaultRetryPolicy } from "./retry.ts";
import type { InboxRetryPolicy } from "./retry.ts";
import { sanitizeError } from "./sanitizer.ts";
import { defaultSchema, inboxSql } from "./sql.ts";
import type { InboxSchema, InboxStatements } from "./sql.ts";

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
export type InboxHandler = (
  message: InboxMessage,
  transaction: InboxTransaction,
  signal: AbortSignal,
) => Promise<void>;

interface ClaimedMessage {
  readonly message: InboxMessage;
  readonly token: string;
}

const noopLogger: InboxLogger = {
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};

/**
 * Claims QueueBox pull messages, hands each one to a handler inside a transaction, and
 * completes, retries or dead-letters it.
 */
export class InboxWorker {
  readonly #connections: InboxConnectionSource;
  readonly #options: ReturnType<typeof resolveOptions>;
  readonly #schema: InboxSchema;
  readonly #sql: InboxStatements;
  readonly #policy: InboxRetryPolicy;
  readonly #logger: InboxLogger;

  /**
   * @param connections opens the connections the worker needs
   * @param options the settings
   */
  constructor(connections: InboxConnectionSource, options: InboxOptions) {
    this.#connections = connections;
    this.#options = resolveOptions(options);
    this.#schema = options.schema ?? defaultSchema;
    this.#sql = inboxSql(this.#options.dialect, this.#schema);
    this.#policy = options.retryPolicy ?? defaultRetryPolicy();
    this.#logger = options.logger ?? noopLogger;
  }

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
  async run(handler: InboxHandler, signal?: AbortSignal): Promise<void> {
    // The handlers do not share the caller's signal. A shutdown must let them finish, so the
    // abort reaches them only after the grace has run out.
    const handlerStop = new AbortController();
    let graceTimer: ReturnType<typeof setTimeout> | undefined;

    const onStop = (): void => {
      graceTimer = setTimeout(() => handlerStop.abort(), this.#options.shutdownGraceMs);
      // A pending timer must not hold the process open.
      graceTimer.unref?.();
    };

    if (signal?.aborted === true) {
      onStop();
    } else {
      signal?.addEventListener("abort", onStop, { once: true });
    }

    try {
      while (signal?.aborted !== true) {
        let batch: ClaimedMessage[];

        try {
          batch = await this.#claim();
        } catch (error) {
          this.#logger.error("The claim failed.", { error: sanitizeError(error) });
          await delay(this.#options.pollIntervalMs, signal);
          continue;
        }

        if (batch.length === 0) {
          // No polling storm. The caller owns the interval.
          await delay(this.#options.pollIntervalMs, signal);
          continue;
        }

        await this.#runBatch(batch, handler, handlerStop.signal);
      }
    } finally {
      if (graceTimer !== undefined) {
        clearTimeout(graceTimer);
      }

      signal?.removeEventListener("abort", onStop);
    }
  }

  async #runBatch(batch: ClaimedMessage[], handler: InboxHandler, stop: AbortSignal): Promise<void> {
    const queue = [...batch];
    const workers: Array<Promise<void>> = [];

    for (let slot = 0; slot < Math.min(this.#options.concurrency, queue.length); slot += 1) {
      workers.push((async () => {
        for (;;) {
          const claimed = queue.shift();

          if (claimed === undefined) {
            return;
          }

          try {
            await this.#process(claimed, handler, stop);
          } catch (error) {
            // A failure of the library itself, not of the handler. The message keeps its lease
            // and returns when the lease expires.
            this.#logger.error("The message was abandoned.", {
              messageId: claimed.message.id,
              error: sanitizeError(error),
            });
          }
        }
      })());
    }

    await Promise.all(workers);
  }

  async #process(claimed: ClaimedMessage, handler: InboxHandler, stop: AbortSignal): Promise<void> {
    const connection = await this.#connections.connect();
    const lease = new LeaseRenewal(
      {
        connections: this.#connections,
        renewText: this.#sql.renew.text,
        leaseMs: this.#options.leaseMs,
        intervalMs: this.#options.renewalIntervalMs,
        logger: this.#logger,
      },
      claimed,
      stop,
    );

    try {
      await connection.begin();

      try {
        await handler(claimed.message, connection, lease.signal);
      } catch (error) {
        await connection.rollback();
        await lease.stop();

        if (lease.ownershipLost) {
          this.#logger.warn("The lease was lost, so nothing was written.", { messageId: claimed.message.id });
          return;
        }

        if (stop.aborted) {
          // The shutdown ran out of grace. The message is abandoned, not failed, so it spends no
          // attempt and reaches no dead letter. Its lease expires and another worker takes it.
          this.#logger.info("The shutdown abandoned the message, so it spends no attempt.", {
            messageId: claimed.message.id,
          });
          return;
        }

        await this.#applyFailure(claimed, error);
        return;
      }

      await lease.stop();

      if (lease.ownershipLost) {
        // The renewal already told us that another worker owns the message. The application's
        // writes must go first.
        await connection.rollback();
        this.#logger.warn("The lease was lost, so the work was rolled back.", { messageId: claimed.message.id });
        return;
      }

      // Section 2. The completion runs inside the handler's transaction, so the application's
      // writes and the completion commit together or neither of them does.
      const result = await connection.query(this.#sql.complete.text, [claimed.message.id, claimed.token]);

      if (result.rowCount === 1) {
        await connection.commit();
        return;
      }

      await connection.rollback();
      this.#logger.warn("The completion affected no row, so the work was rolled back.", {
        messageId: claimed.message.id,
      });
    } finally {
      await lease.stop();
      await connection.release();
    }
  }

  async #applyFailure(claimed: ClaimedMessage, failure: unknown): Promise<void> {
    const action = this.#policy(claimed.message, failure);
    const error = sanitizeError(failure) ?? null;
    const connection = await this.#connections.connect();

    try {
      await connection.begin();

      const result = action.retry
        ? await connection.query(this.#sql.retry.text, [action.delayMs, error, claimed.message.id, claimed.token])
        : await connection.query(this.#sql.dead.text, [error, claimed.message.id, claimed.token]);

      await connection.commit();

      if (result.rowCount === 0) {
        // The claim was already lost. Nothing changed, which is correct.
        this.#logger.warn("The failure changed nothing, because the claim was lost.", {
          messageId: claimed.message.id,
        });
      } else if (action.retry) {
        this.#logger.warn("The message retries.", { messageId: claimed.message.id, delayMs: action.delayMs });
      } else {
        this.#logger.error("The message reached the dead letter.", { messageId: claimed.message.id });
      }
    } finally {
      await connection.release();
    }
  }

  async #claim(): Promise<ClaimedMessage[]> {
    // The claim runs in a short transaction of its own and commits before any handler starts.
    const connection = await this.#connections.connect();

    try {
      await connection.begin();

      const result = await connection.query(this.#sql.claim.text, [
        this.#options.source,
        this.#options.batchSize,
        this.#options.leaseMs,
      ]);

      await connection.commit();

      return result.rows.map((row) => this.#read(row));
    } finally {
      await connection.release();
    }
  }

  #read(row: Record<string, unknown>): ClaimedMessage {
    const s = this.#schema;
    const text = (value: unknown): string | null => (value === null || value === undefined ? null : String(value));
    const raw = row[s.payload];

    return {
      message: {
        id: String(row[s.id]),
        source: String(row[s.source]),
        idempotencyKey: String(row[s.idempotencyKey]),
        aggregateId: text(row[s.aggregateId]),
        eventType: text(row[s.eventType]),
        // PostgreSQL returns jsonb already parsed. SQL Server returns the text of an nvarchar.
        payload: typeof raw === "string" ? JSON.parse(raw) : raw,
        attempt: Number(row[s.attempt]),
        correlationId: text(row[s.correlationId]),
      },
      token: String(row[s.claimToken]),
    };
  }

}

/**
 * Renews the lease while the handler runs. A renewal that affects zero rows means the ownership
 * is lost, so the renewal aborts the handler and reports the loss.
 */
interface RenewalContext {
  readonly connections: InboxConnectionSource;
  readonly renewText: string;
  readonly leaseMs: number;
  readonly intervalMs: number;
  readonly logger: InboxLogger;
}

class LeaseRenewal {
  readonly #context: RenewalContext;
  readonly #claimed: ClaimedMessage;
  readonly #controller: AbortController;
  readonly #stopRenewal = new AbortController();
  readonly #loop: Promise<void>;
  #lost = false;
  #stopped = false;

  constructor(context: RenewalContext, claimed: ClaimedMessage, stop: AbortSignal) {
    this.#context = context;
    this.#claimed = claimed;
    this.#controller = new AbortController();

    const onStop = (): void => this.#controller.abort();

    if (stop.aborted) {
      onStop();
    } else {
      stop.addEventListener("abort", onStop, { once: true });
    }

    this.#loop = this.#renew();
  }

  get signal(): AbortSignal {
    return this.#controller.signal;
  }

  get ownershipLost(): boolean {
    return this.#lost;
  }

  /** The renewal timer stops when the handler returns, however it returns. */
  async stop(): Promise<void> {
    if (this.#stopped) {
      return;
    }

    this.#stopped = true;
    this.#stopRenewal.abort();
    await this.#loop;
  }

  async #renew(): Promise<void> {
    const { connections, renewText, leaseMs, intervalMs, logger } = this.#context;

    while (!this.#stopRenewal.signal.aborted) {
      await delay(intervalMs, this.#stopRenewal.signal);

      if (this.#stopRenewal.signal.aborted) {
        return;
      }

      let rowCount: number;

      try {
        // The renewal needs its own connection. The handler's connection is inside the handler's
        // transaction, and a renewal there would commit with it.
        const connection = await connections.connect();

        try {
          const result = await connection.query(renewText, [
            leaseMs,
            this.#claimed.message.id,
            this.#claimed.token,
          ]);
          rowCount = result.rowCount;
        } finally {
          await connection.release();
        }
      } catch (error) {
        // A renewal that could not run is not a lost lease. The next one tries again, and the
        // lease expires on its own if none of them succeeds.
        logger.warn("The renewal failed.", { messageId: this.#claimed.message.id, error: sanitizeError(error) });
        continue;
      }

      if (rowCount === 0) {
        this.#lost = true;
        logger.warn("The lease was lost to another worker.", { messageId: this.#claimed.message.id });
        this.#controller.abort();
        return;
      }
    }
  }
}

/** Wait, and return early when the signal aborts. */
function delay(ms: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted === true) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    const timer = setTimeout(finish, ms);
    timer.unref?.();

    function finish(): void {
      clearTimeout(timer);
      signal?.removeEventListener("abort", finish);
      resolve();
    }

    signal?.addEventListener("abort", finish, { once: true });
  });
}
