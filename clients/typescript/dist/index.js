// src/options.ts
function resolveOptions(options) {
  if (typeof options.source !== "string" || options.source.trim() === "") {
    throw new TypeError("The source is mandatory.");
  }
  const batchSize = options.batchSize ?? 10;
  const leaseMs = options.leaseMs ?? 3e4;
  const pollIntervalMs = options.pollIntervalMs ?? 1e3;
  const shutdownGraceMs = options.shutdownGraceMs ?? 3e4;
  if (!Number.isInteger(batchSize) || batchSize <= 0) {
    throw new RangeError("The batchSize must be a positive whole number.");
  }
  if (!Number.isInteger(leaseMs) || leaseMs < 3) {
    throw new RangeError("The leaseMs must be a whole number of three or more.");
  }
  if (options.maxConcurrency !== void 0 && (!Number.isInteger(options.maxConcurrency) || options.maxConcurrency <= 0)) {
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
    concurrency: Math.min(options.maxConcurrency ?? batchSize, batchSize),
    pollIntervalMs,
    shutdownGraceMs,
    dialect: options.dialect ?? "postgresql",
    renewalIntervalMs: Math.max(1, Math.floor(leaseMs / 3))
  };
}

// src/retry.ts
function retryAfter(delayMs) {
  if (!Number.isFinite(delayMs) || delayMs < 0) {
    throw new RangeError("The delay must not be negative.");
  }
  return { retry: true, delayMs: Math.round(delayMs) };
}
function deadLetter() {
  return { retry: false };
}
function defaultRetryPolicy(options = {}) {
  const maxAttempts = options.maxAttempts ?? 5;
  const baseDelayMs = options.baseDelayMs ?? 1e3;
  const maxDelayMs = options.maxDelayMs ?? 3e5;
  const jitter = options.jitter ?? 0.2;
  if (!Number.isInteger(maxAttempts) && maxAttempts !== Number.MAX_SAFE_INTEGER) {
    throw new RangeError("The maxAttempts must be a whole number.");
  }
  if (maxAttempts <= 0) {
    throw new RangeError("The maxAttempts must be positive.");
  }
  if (baseDelayMs < 0) {
    throw new RangeError("The baseDelayMs must not be negative.");
  }
  if (maxDelayMs < baseDelayMs) {
    throw new RangeError("The maxDelayMs must not be below the baseDelayMs.");
  }
  if (jitter < 0 || jitter > 1) {
    throw new RangeError("The jitter must be between zero and one.");
  }
  return (message) => {
    if (message.attempt >= maxAttempts) {
      return deadLetter();
    }
    const exponent = Math.min(message.attempt, 30);
    const scaled = baseDelayMs * 2 ** exponent;
    const bounded = Math.min(scaled, maxDelayMs);
    if (jitter <= 0) {
      return retryAfter(bounded);
    }
    const offset = (Math.random() * 2 - 1) * bounded * jitter;
    return retryAfter(Math.max(0, bounded + offset));
  };
}

// src/sanitizer.ts
var MAX_ERROR_LENGTH = 2e3;
var REDACTED = "[REDACTED]";
var TRUNCATION_MARKER = "...[truncated]";
var MAX_CAUSE_DEPTH = 5;
var MASK = "***";
var SECRET_KEYS = [
  "authorization",
  "proxy-authorization",
  "x-api-key",
  "api-key",
  "apikey",
  "cookie",
  "set-cookie",
  "x-auth-token",
  "token",
  "access_token",
  "refresh_token",
  "client_secret",
  "secret",
  "password",
  "pwd",
  "passwd",
  "credential",
  "credentials",
  "passphrase",
  "private_key"
];
var AUTH_SCHEMES = ["Basic", "Bearer", "Digest", "Negotiate", "Token"];
function escape(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
function keyAlternative(key) {
  return key.split(/[-_.]/).map(escape).join("[-_.]?");
}
var QUOTED_VALUE = `"(?:\\\\.|[^"\\\\\\n])*"?|'(?:\\\\.|[^'\\\\\\n])*'?`;
var SECRET_PATTERN = new RegExp(
  `([A-Za-z0-9_]*(?:${SECRET_KEYS.map(keyAlternative).join("|")}))\\b"?\\s*(?::+\\s*(?:${QUOTED_VALUE}|(?:(?:${AUTH_SCHEMES.join("|")})\\s+)?[^,;}\\]&\\n"]*)|=+\\s*(?:${QUOTED_VALUE}|(?:(?:${AUTH_SCHEMES.join("|")})\\s+)?[^\\s,;}\\]&\\n"]*))`,
  "gi"
);
var SCHEME_PATTERN = new RegExp(
  `\\b(${AUTH_SCHEMES.join("|")})\\s+(?=[A-Za-z0-9._~+/\\-]*[0-9=]|[A-Za-z0-9._~+/\\-]{16})[A-Za-z0-9._~+/\\-]+=*`,
  "gi"
);
var SCHEME_PART = "([a-zA-Z][a-zA-Z0-9+.-]*:/{1,2})";
var USER_PART = "[^\\s/?#@]*:";
var HOST_AFTER = "(?=[^/?#\\s@]*(?:[/?#\\s]|$))";
var PLAUSIBLE_HOST_AFTER = "(?=[^/?#\\s@]*:[0-9]+(?:[/?#\\s]|$)|[^/?#\\s@]*[/?#])";
var SLASH_RUN = "(?:(?!://)(?!,\\s)[^?#]){0,200}";
var NO_SLASH_RUN = "(?:(?!,\\s)[^/?#]){0,200}";
var USER_INFO_SPACE_WITH_SLASH = new RegExp(
  SCHEME_PART + USER_PART + SLASH_RUN + "\\s" + SLASH_RUN + "@" + PLAUSIBLE_HOST_AFTER,
  "g"
);
var USER_INFO_SPACE_NO_SLASH = new RegExp(
  SCHEME_PART + USER_PART + NO_SLASH_RUN + "\\s" + NO_SLASH_RUN + "@" + HOST_AFTER,
  "g"
);
var USER_INFO_NO_SPACE = new RegExp(
  SCHEME_PART + "(?:[^\\s/@]*:(?:(?!://)\\S)*|[^\\s/@]*)@" + HOST_AFTER,
  "g"
);
var PASSWORD_PARAMETER = /([?&;](?:password|pwd|secret|token))=[^&;\s]*/gi;
function maskUrl(url) {
  return url.replace(USER_INFO_SPACE_WITH_SLASH, `$1${MASK}@`).replace(USER_INFO_SPACE_NO_SLASH, `$1${MASK}@`).replace(USER_INFO_NO_SPACE, `$1${MASK}@`).replace(PASSWORD_PARAMETER, `$1=${MASK}`);
}
function sanitize(text) {
  if (text === void 0) {
    return void 0;
  }
  let redacted = text.replace(SECRET_PATTERN, (_match, key) => `${key}=${REDACTED}`);
  redacted = maskUrl(redacted);
  redacted = redacted.replace(SCHEME_PATTERN, (_match, scheme) => `${scheme} ${REDACTED}`);
  return redacted.length <= MAX_ERROR_LENGTH ? redacted : redacted.slice(0, MAX_ERROR_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER;
}
function sanitizeError(failure) {
  const parts = [];
  let current = failure;
  let depth = 0;
  while (current !== void 0 && current !== null && depth < MAX_CAUSE_DEPTH) {
    if (current instanceof Error) {
      parts.push(`${current.name}: ${current.message}`);
      const next = current.cause;
      current = next === current ? void 0 : next;
    } else {
      parts.push(String(current));
      current = void 0;
    }
    depth += 1;
  }
  return sanitize(parts.join(" | caused by "));
}

// src/sql.ts
var defaultSchema = Object.freeze({
  table: "inbox",
  id: "id",
  consumption: "consumption",
  source: "source",
  state: "state",
  scheduledAt: "scheduled_at",
  createdAt: "created_at",
  claimToken: "claim_token",
  claimedAt: "claimed_at",
  leaseExpiresAt: "lease_expires_at",
  processedAt: "processed_at",
  attempt: "attempt",
  lastError: "last_error",
  idempotencyKey: "idempotency_key",
  aggregateId: "aggregate_id",
  eventType: "event_type",
  payload: "payload",
  correlationId: "correlation_id"
});
var SAFE_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_$]{0,62}$/;
function validate(schema) {
  for (const [property, value] of Object.entries(schema)) {
    if (typeof value !== "string" || !SAFE_IDENTIFIER.test(value)) {
      throw new TypeError(`The schema name '${property}' is not a plain SQL identifier.`);
    }
  }
}
function inboxSql(dialect, schema) {
  validate(schema);
  return dialect === "postgresql" ? postgresql(schema) : sqlserver(schema);
}
function postgresql(s) {
  const q = (name) => `"${name}"`;
  const fence = (id, token) => `
WHERE ${q(s.id)} = ${id} AND ${q(s.consumption)} = 'pull' AND ${q(s.state)} = 'processing'
  AND ${q(s.claimToken)} = ${token} AND ${q(s.leaseExpiresAt)} > clock_timestamp()`;
  return {
    claim: {
      text: `
WITH candidates AS (
    SELECT ${q(s.id)} FROM ${q(s.table)}
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = $1
      AND ((${q(s.state)} = 'pending' AND ${q(s.scheduledAt)} <= clock_timestamp())
        OR (${q(s.state)} = 'processing' AND ${q(s.leaseExpiresAt)} <= clock_timestamp()))
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
UPDATE ${q(s.table)} AS target
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = gen_random_uuid(),
    ${q(s.claimedAt)} = clock_timestamp(),
    ${q(s.leaseExpiresAt)} = clock_timestamp() + $3 * INTERVAL '1 millisecond'
FROM candidates WHERE target.${q(s.id)} = candidates.${q(s.id)}
RETURNING target.*`,
      params: ["source", "batch", "leaseMs"]
    },
    renew: {
      text: `UPDATE ${q(s.table)} SET ${q(s.leaseExpiresAt)} = clock_timestamp() + $1 * INTERVAL '1 millisecond'${fence("$2", "$3")}`,
      params: ["leaseMs", "id", "token"]
    },
    complete: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'processed', ${q(s.processedAt)} = clock_timestamp(), ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$1", "$2")}`,
      params: ["id", "token"]
    },
    retry: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'pending', ${q(s.scheduledAt)} = clock_timestamp() + $1 * INTERVAL '1 millisecond', ${q(s.attempt)} = ${q(s.attempt)} + 1, ${q(s.lastError)} = $2, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$3", "$4")}`,
      params: ["delayMs", "error", "id", "token"]
    },
    dead: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'dead', ${q(s.lastError)} = $1, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("$2", "$3")}`,
      params: ["error", "id", "token"]
    }
  };
}
function sqlserver(s) {
  const q = (name) => `[${name}]`;
  const fence = (id, token) => `
WHERE ${q(s.id)} = ${id} AND ${q(s.consumption)} = 'pull' AND ${q(s.state)} = 'processing'
  AND ${q(s.claimToken)} = ${token} AND ${q(s.leaseExpiresAt)} > SYSUTCDATETIME()`;
  return {
    claim: {
      text: `
WITH candidates AS (
    SELECT TOP (@p2) * FROM ${q(s.table)} WITH (UPDLOCK, READPAST, ROWLOCK)
    WHERE ${q(s.consumption)} = 'pull' AND ${q(s.source)} = @p1
      AND ((${q(s.state)} = 'pending' AND ${q(s.scheduledAt)} <= SYSUTCDATETIME())
        OR (${q(s.state)} = 'processing' AND ${q(s.leaseExpiresAt)} <= SYSUTCDATETIME()))
    ORDER BY ${q(s.scheduledAt)}, ${q(s.createdAt)}
)
UPDATE candidates
SET ${q(s.state)} = 'processing', ${q(s.claimToken)} = NEWID(), ${q(s.claimedAt)} = SYSUTCDATETIME(),
    ${q(s.leaseExpiresAt)} = DATEADD(millisecond, @p3, SYSUTCDATETIME())
OUTPUT INSERTED.*`,
      params: ["source", "batch", "leaseMs"]
    },
    renew: {
      text: `UPDATE ${q(s.table)} SET ${q(s.leaseExpiresAt)} = DATEADD(millisecond, @p1, SYSUTCDATETIME())${fence("@p2", "@p3")}`,
      params: ["leaseMs", "id", "token"]
    },
    complete: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'processed', ${q(s.processedAt)} = SYSUTCDATETIME(), ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p1", "@p2")}`,
      params: ["id", "token"]
    },
    retry: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'pending', ${q(s.scheduledAt)} = DATEADD(millisecond, @p1, SYSUTCDATETIME()), ${q(s.attempt)} = ${q(s.attempt)} + 1, ${q(s.lastError)} = @p2, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p3", "@p4")}`,
      params: ["delayMs", "error", "id", "token"]
    },
    dead: {
      text: `UPDATE ${q(s.table)} SET ${q(s.state)} = 'dead', ${q(s.lastError)} = @p1, ${q(s.claimToken)} = NULL, ${q(s.leaseExpiresAt)} = NULL${fence("@p2", "@p3")}`,
      params: ["error", "id", "token"]
    }
  };
}

// src/worker.ts
var noopLogger = {
  info: () => void 0,
  warn: () => void 0,
  error: () => void 0
};
var InboxWorker = class {
  #connections;
  #options;
  #schema;
  #sql;
  #policy;
  #logger;
  /**
   * @param connections opens the connections the worker needs
   * @param options the settings
   */
  constructor(connections, options) {
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
  async run(handler, signal) {
    const handlerStop = new AbortController();
    let graceTimer;
    const onStop = () => {
      graceTimer = setTimeout(() => handlerStop.abort(), this.#options.shutdownGraceMs);
      graceTimer.unref?.();
    };
    if (signal?.aborted === true) {
      onStop();
    } else {
      signal?.addEventListener("abort", onStop, { once: true });
    }
    try {
      while (signal?.aborted !== true) {
        let batch;
        try {
          batch = await this.#claim();
        } catch (error) {
          this.#logger.error("The claim failed.", { error: sanitizeError(error) });
          await delay(this.#options.pollIntervalMs, signal);
          continue;
        }
        if (batch.length === 0) {
          await delay(this.#options.pollIntervalMs, signal);
          continue;
        }
        await this.#runBatch(batch, handler, handlerStop.signal);
      }
    } finally {
      if (graceTimer !== void 0) {
        clearTimeout(graceTimer);
      }
      signal?.removeEventListener("abort", onStop);
    }
  }
  async #runBatch(batch, handler, stop) {
    const queue = [...batch];
    const workers = [];
    for (let slot = 0; slot < Math.min(this.#options.concurrency, queue.length); slot += 1) {
      workers.push((async () => {
        for (; ; ) {
          const claimed = queue.shift();
          if (claimed === void 0) {
            return;
          }
          try {
            await this.#process(claimed, handler, stop);
          } catch (error) {
            this.#logger.error("The message was abandoned.", {
              messageId: claimed.message.id,
              error: sanitizeError(error)
            });
          }
        }
      })());
    }
    await Promise.all(workers);
  }
  async #process(claimed, handler, stop) {
    const connection = await this.#connections.connect();
    const lease = new LeaseRenewal(
      {
        connections: this.#connections,
        renewText: this.#sql.renew.text,
        leaseMs: this.#options.leaseMs,
        intervalMs: this.#options.renewalIntervalMs,
        logger: this.#logger
      },
      claimed,
      stop
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
          this.#logger.info("The shutdown abandoned the message, so it spends no attempt.", {
            messageId: claimed.message.id
          });
          return;
        }
        await this.#applyFailure(claimed, error);
        return;
      }
      await lease.stop();
      if (lease.ownershipLost) {
        await connection.rollback();
        this.#logger.warn("The lease was lost, so the work was rolled back.", { messageId: claimed.message.id });
        return;
      }
      const result = await connection.query(this.#sql.complete.text, [claimed.message.id, claimed.token]);
      if (result.rowCount === 1) {
        await connection.commit();
        return;
      }
      await connection.rollback();
      this.#logger.warn("The completion affected no row, so the work was rolled back.", {
        messageId: claimed.message.id
      });
    } finally {
      await lease.stop();
      await connection.release();
    }
  }
  async #applyFailure(claimed, failure) {
    const action = this.#policy(claimed.message, failure);
    const error = sanitizeError(failure) ?? null;
    const connection = await this.#connections.connect();
    try {
      await connection.begin();
      const result = action.retry ? await connection.query(this.#sql.retry.text, [action.delayMs, error, claimed.message.id, claimed.token]) : await connection.query(this.#sql.dead.text, [error, claimed.message.id, claimed.token]);
      await connection.commit();
      if (result.rowCount === 0) {
        this.#logger.warn("The failure changed nothing, because the claim was lost.", {
          messageId: claimed.message.id
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
  async #claim() {
    const connection = await this.#connections.connect();
    try {
      await connection.begin();
      const result = await connection.query(this.#sql.claim.text, [
        this.#options.source,
        this.#options.batchSize,
        this.#options.leaseMs
      ]);
      await connection.commit();
      return result.rows.map((row) => this.#read(row));
    } finally {
      await connection.release();
    }
  }
  #read(row) {
    const s = this.#schema;
    const text = (value) => value === null || value === void 0 ? null : String(value);
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
        correlationId: text(row[s.correlationId])
      },
      token: String(row[s.claimToken])
    };
  }
};
var LeaseRenewal = class {
  #context;
  #claimed;
  #controller;
  #stopRenewal = new AbortController();
  #loop;
  #lost = false;
  #stopped = false;
  constructor(context, claimed, stop) {
    this.#context = context;
    this.#claimed = claimed;
    this.#controller = new AbortController();
    const onStop = () => this.#controller.abort();
    if (stop.aborted) {
      onStop();
    } else {
      stop.addEventListener("abort", onStop, { once: true });
    }
    this.#loop = this.#renew();
  }
  get signal() {
    return this.#controller.signal;
  }
  get ownershipLost() {
    return this.#lost;
  }
  /** The renewal timer stops when the handler returns, however it returns. */
  async stop() {
    if (this.#stopped) {
      return;
    }
    this.#stopped = true;
    this.#stopRenewal.abort();
    await this.#loop;
  }
  async #renew() {
    const { connections, renewText, leaseMs, intervalMs, logger } = this.#context;
    while (!this.#stopRenewal.signal.aborted) {
      await delay(intervalMs, this.#stopRenewal.signal);
      if (this.#stopRenewal.signal.aborted) {
        return;
      }
      let rowCount;
      try {
        const connection = await connections.connect();
        try {
          const result = await connection.query(renewText, [
            leaseMs,
            this.#claimed.message.id,
            this.#claimed.token
          ]);
          rowCount = result.rowCount;
        } finally {
          await connection.release();
        }
      } catch (error) {
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
};
function delay(ms, signal) {
  if (signal?.aborted === true) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const timer = setTimeout(finish, ms);
    timer.unref?.();
    function finish() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", finish);
      resolve();
    }
    signal?.addEventListener("abort", finish, { once: true });
  });
}

// src/connections.ts
function fromPg(pool) {
  return {
    async connect() {
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
        }
      };
    }
  };
}
function fromMssql(mssql, pool) {
  return {
    async connect() {
      let transaction;
      const run = async (text, params = []) => {
        const request = transaction === void 0 ? pool.request() : new mssql.Request(transaction);
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
          transaction = void 0;
        },
        async rollback() {
          await transaction?.rollback();
          transaction = void 0;
        },
        async release() {
          if (transaction !== void 0) {
            await transaction.rollback().catch(() => void 0);
            transaction = void 0;
          }
        }
      };
    }
  };
}
export {
  InboxWorker,
  MAX_ERROR_LENGTH,
  deadLetter,
  defaultRetryPolicy,
  defaultSchema,
  fromMssql,
  fromPg,
  inboxSql,
  resolveOptions,
  retryAfter,
  sanitize,
  sanitizeError
};
//# sourceMappingURL=index.js.map