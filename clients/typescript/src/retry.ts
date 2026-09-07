import type { InboxMessage } from "./message.ts";

/** What the library does with a message whose handler threw. */
export type InboxFailureAction =
  | { readonly retry: true; readonly delayMs: number }
  | { readonly retry: false };

/** Return the message to pending after a delay. */
export function retryAfter(delayMs: number): InboxFailureAction {
  if (!Number.isFinite(delayMs) || delayMs < 0) {
    throw new RangeError("The delay must not be negative.");
  }

  return { retry: true, delayMs: Math.round(delayMs) };
}

/** Move the message to the dead letter and stop. */
export function deadLetter(): InboxFailureAction {
  return { retry: false };
}

/**
 * Decides what happens to a message whose handler threw.
 *
 * The library exposes the decision, because only the application knows that a validation error
 * must never be retried while a timeout must.
 */
export type InboxRetryPolicy = (message: InboxMessage, failure: unknown) => InboxFailureAction;

/** The settings of the default policy. */
export interface DefaultRetryPolicyOptions {
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
export function defaultRetryPolicy(options: DefaultRetryPolicyOptions = {}): InboxRetryPolicy {
  const maxAttempts = options.maxAttempts ?? 5;
  const baseDelayMs = options.baseDelayMs ?? 1000;
  const maxDelayMs = options.maxDelayMs ?? 300_000;
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

    // A large attempt must not overflow the shift, so the exponent stops where the ceiling is
    // certainly reached.
    const exponent = Math.min(message.attempt, 30);
    const scaled = baseDelayMs * 2 ** exponent;
    const bounded = Math.min(scaled, maxDelayMs);

    if (jitter <= 0) {
      return retryAfter(bounded);
    }

    // A full band around the delay: one worker's backoff never lines up with another's.
    const offset = (Math.random() * 2 - 1) * bounded * jitter;

    return retryAfter(Math.max(0, bounded + offset));
  };
}
