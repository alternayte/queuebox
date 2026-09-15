# Inbox headers

## What it does
Every inbox source stores the headers of each received message in a `headers` column. Each source can have a header filter. A message that does not pass the filter is acknowledged and not stored. Pull clients, inbox transforms and the relay expose the stored headers.

## Decisions
- The inbox stores headers as `Map<String, String>` in `headers` (`jsonb` on Postgres, `text` on SQL Server) — the same shape as the outbox, so a relayed message keeps its headers.
- A repeated key keeps its last value — one value per key keeps the filter rules simple.
- Kafka values decode as UTF-8. A value that is not valid UTF-8 is stored as `base64:<value>` — no header is lost.
- RabbitMQ values become strings. Nested tables become JSON text — typed AMQP values fit the string map.
- The HTTP webhook source stores its request headers, except the auth headers — secrets never reach the table.
- Each source config has `filter.require` and `filter.exclude` lists — this gives an allow list and a deny list without an expression language.
- A message passes if it matches every `require` entry and no `exclude` entry — the rule has one reading.
- A rule names a header key and one test: `equals`, `in`, `matches` (glob) or `exists` — each test can be set with `QUEUEBOX_*` env vars.
- Header keys match case-insensitively. Values match exactly — this is the same as the current correlation-id lookup.
- The filter runs before idempotency extraction and before transforms — a dropped message costs no further work.
- A broker acknowledges a filtered message. The webhook returns `202 Accepted` — the sender does not retry.
- A filtered message increments `queuebox_inbox_filtered_total{source}` and writes one debug log line with the message id and the first failed rule — operators can see drops without stored rows.
- The inbox table gets no row for a filtered message, not even a dead row — the filter removes traffic that the inbox must not hold.
- Migration V10 adds `headers` to the inbox table on Postgres and SQL Server, with default `{}` — managed schemas upgrade without action.
- `InboxColumnMapping` gets a required `headers` entry. The startup schema check fails if the column is missing, and it prints the `ALTER TABLE` statement — an optional column would add a silent path through every consumer.
- The Go, TypeScript and C# pull clients get a `headers` field on the message — a pull worker reads the headers without its own SQL.
- Inbox JSONata transforms get `$headers` in their context — a transform can use a header value.
- The relay copies inbox headers onto the outbox row. `x-inbox-id`, `x-source`, `x-idempotency-key` and the correlation header take precedence — the relay headers stay trustworthy.
- The changelog marks the required column as a breaking change — custom inbox tables need an `ALTER TABLE`.

## Out
- New outbox header features. Apps already insert any header in the outbox `headers` column.
- Filters on outbox routes.
- Filters on the payload.
- More than one value per header key.
- Header size limits.

## How I know it works
- A RabbitMQ, Kafka, NATS and webhook message each produce an inbox row whose `headers` holds the sent headers.
- A Kafka header with invalid UTF-8 bytes is stored with the `base64:` prefix.
- With `filter.require` set to `x-tenant equals acme`, a message with `x-tenant: other` gives no row, and `queuebox_inbox_filtered_total{source}` increases by one.
- With `filter.exclude` set to `x-test exists`, a message with `x-test` gives no row. The broker queue is empty after delivery.
- A webhook call that is filtered returns `202`.
- The filter set with `QUEUEBOX_*` env vars behaves the same as the filter set in YAML.
- A pull client in Go, TypeScript and C# reads `headers` from a claimed message.
- An inbox transform that outputs `$headers."x-tenant"` gives that value in the stored payload.
- A relayed message arrives at the destination with the original headers, and `x-inbox-id` has the relay value even if the source sent its own.
- A custom inbox table without `headers` stops startup with a message that names the column and gives the `ALTER TABLE` statement.
- `./gradlew build detekt` passes.
