/**
 * One claimed inbox row, as the handler sees it.
 *
 * The claim token is deliberately absent. The library owns the token, because a handler that
 * could reach it could complete a message out of band.
 */
export interface InboxMessage {
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
