# Inbox keys and initial delay

## What it does
An inbox source computes its idempotency key, event type and aggregate ID with a JSONata expression. Three optional keys hold the expressions: `idempotencyKeyExpression`, `eventTypeExpression` and `aggregateIdExpression`. Each of the three path keys also takes a list of paths, and QueueBox takes the first path that gives a value. An optional `initialDelay` on a source holds each received row until the receipt time plus the delay. Fixes #83, #84 and #85. The release is 0.7.0.

## Decisions
- The expression keys are new keys on all four source kinds — every current configuration stays valid, and a path key keeps one language.
- QueueBox evaluates an expression on the original payload, before the source transform — the transform still reads `$eventType` and `$idempotencyKey` (#64).
- An expression sits directly before its path key in the chain of the attribute, and the rest of the chain does not change. On an HTTP source the order is header, expression, path — the more specific setting wins, as the header does today.
- An expression binds no variables. The payload is the only input — no request needs the headers, and a header key already exists.
- A result that is a string, a number or a boolean becomes the value. A whole number has no decimal point. Any other result, and a blank string, counts as no value — an object is not a key, and a blank key merges unrelated messages.
- An expression that fails or passes its limit counts as no value, and QueueBox writes one warn line with the source and the key name. The limits are 100 ms and depth 100, the defaults of a transform — a bad message must not stop the source.
- An expression that does not compile stops the start and names the key — an operator sees a typing error before the first message.
- `idempotencyKeyExpression` satisfies the HTTP check for an idempotency key, and `eventTypeExpression` satisfies the `{{ eventType }}` topic check — setting the key is the declaration, as with the header keys.
- A path key takes a string or a list of strings. Every path in a list is definite, a list has at least one path, and the start stops otherwise — the current check applies per path.
- A list loads from `QUEUEBOX_SOURCES_<NAME>_AGGREGATEIDPATH_0`, `_1` and so on. The name without an index still sets one path — the loader already builds lists from indexes.
- `initialDelay` takes a duration such as `30s`, with the suffixes `s`, `m`, `h` and `d` — the retention keys use the same form.
- The store sets `scheduled_at` to the database time plus the delay — the claim compares against the database clock.
- The relay claim takes a row only when `scheduled_at` has passed, on PostgreSQL and on SQL Server — it ignores `scheduled_at` today, so a push source would not hold a row.
- A dead row gets no delay — no claim reads it.
- No migration and no client library change — `scheduled_at` and its index exist since V6, and the pull claim already honours the column.
- The changelog lists all three under 0.7.0 as Added. The release ships the server image alone, as `v0.7.0`.
- The docs site gains the keys in the configuration and environment variable references, and in the bridge, consume and ordering pages.

## Out
- Key extraction from the transform output.
- A JSONata expression inside a path key.
- Variables such as `$headers` in a key expression.
- Per-expression `timeoutMs` and `maxDepth` settings.
- A delay per event type or per message.
- A list of fallback headers.

## How I know it works
- `aggregateIdExpression: $split(subject, "/")[2]` stores `515725` for the subjects `/organization/515725` and `/organization/515725/invitation/66725563`.
- `eventTypeExpression: $lookup({"Organization.Review.accepted.v1": "Review.Accepted"}, type)` stores `Review.Accepted`. A type that the table lacks falls back to `eventTypePath`.
- An expression with a syntax error stops the start with an error that names the key.
- An HTTP source with only `idempotencyKeyExpression` starts. A body for which the expression gives nothing gets `400`.
- `aggregateIdPath: [$.orderId, $.OrderId]` stores the aggregate ID for a body with either name. A single string behaves as before.
- A list with an indefinite path, and an empty list, stop the start.
- The three expression keys, a path list and `initialDelay` load from YAML and from `QUEUEBOX_*` variables to the same config.
- With `initialDelay: 30s`, a stored row has `scheduled_at` 30 seconds after the receipt. The pull claim and the relay skip the row before that time and take it after, on PostgreSQL and on SQL Server.
- A source without `initialDelay` stores `scheduled_at` at the receipt time.
- `initialDelay: 30` and `initialDelay: -5s` stop the start.
- `./gradlew check detekt` passes.
