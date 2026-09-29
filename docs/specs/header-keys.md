# Header keys

## What it does
An HTTP inbox source can read the idempotency key, the event type and the aggregate ID from a request header. Three optional keys name the headers: `idempotencyKeyHeader`, `eventTypeHeader` and `aggregateIdHeader`. They sit beside the path keys. A GitHub source keys on `X-GitHub-Delivery`. Fixes #80.

## Decisions
- Each attribute takes a header key, a path key, or both. With both, the header wins and the path is the fallback — the broker sources read the idempotency key header first too.
- `idempotencyKeyPath` becomes optional on an HTTP source. A source must set `idempotencyKeyPath` or `idempotencyKeyHeader`, or the start stops and names both keys — a header-only source must load.
- Header names match in any letter case — HTTP header names are case-insensitive.
- The value is trimmed. An empty value counts as missing, and the path fallback applies — a blank key would merge unrelated requests.
- The handler reads the header from the stored request headers. A repeated header keeps its last value — the same map the filter and the `headers` column use.
- A missing idempotency key gets the same `400` response body as a missing path today. The log line stays one warn line, and it names the header and the path that the source tried — the operator sees which setting failed.
- No new length check. The 255-character columns bound the idempotency key, the event type and the aggregate ID, as they do for a path value today.
- A key header named `Authorization`, `Proxy-Authorization`, `Cookie` or the `auth.headerName` of an `api-key` or `hmac` block stops the start. The match ignores letter case — QueueBox never stores those headers, so the key would never be found.
- A key header name that is blank stops the start.
- `eventTypeHeader` satisfies the check that a push source with a `{{ eventType }}` topic has a source of the event type — setting the header key is the declaration, as `eventTypeFromHeader` is for a broker.
- The strict config check and the `QUEUEBOX_*` variables pick up the three keys from the constructor of `SourceConfig.Http` — `QUEUEBOX_SOURCES_<NAME>_IDEMPOTENCYKEYHEADER` binds like every other source key.
- The GitHub samples in the packaged config, `examples/queuebox.yml` and the how-to pages switch to `X-GitHub-Delivery` and `X-GitHub-Event` — a GitHub body has no `delivery` field, so the old samples rejected every delivery.
- The changelog lists the feature under 0.6.0 as Added. It is not breaking — every config that loaded before still loads.

## Out
- Header keys on the broker sources. They already have `attributeHeaders`.
- A list of fallback headers per attribute.
- A key built from more than one header.
- A length check before the store.

## How I know it works
- `idempotencyKeyHeader`, `eventTypeHeader` and `aggregateIdHeader` load from YAML and from `QUEUEBOX_*` variables to the same config.
- A source with neither `idempotencyKeyPath` nor `idempotencyKeyHeader` stops the start and names both keys.
- On PostgreSQL and SQL Server, a request with the key header stores the header value. The same request without the header stores the path value.
- On PostgreSQL and SQL Server, the same GitHub delivery, HMAC-signed, sent twice stores one row. The same body with two different `X-GitHub-Delivery` values stores two rows.
- `x-github-delivery` in lower case matches `X-GitHub-Delivery`.
- A header value of spaces with no path fallback gets `400`.
- A missing header with no path fallback gets the same status and body as a missing path, and one warn log line that names the header.
- `idempotencyKeyHeader: X-Hub-Signature-256` with `auth.headerName: X-Hub-Signature-256` stops the start with an error that names the key and the header. `Authorization`, `Proxy-Authorization` and `Cookie` stop it too.
- A `{{ eventType }}` topic with `eventTypeHeader` and no `eventTypePath` starts.
- `./gradlew check detekt` passes.
