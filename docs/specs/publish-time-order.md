# Publish time order

## What it does
An inbox source takes the publish time of a message from a header or from the body. The store sets `scheduled_at` to that time plus the `initialDelay` of the source. A pull claim and the relay claim then take the messages of one aggregate in publish order, for every message that arrives inside the delay. The order does not depend on the prefetch or on the number of QueueBox instances. Fixes #91. The release is 0.8.0.

## Decisions
- Two optional keys on all four source kinds: `scheduledAtHeader` and `scheduledAtPath` — the names of #91, and the same pair as the other inbox keys.
- The header comes first, then the path. `scheduledAtPath` takes one definite JSONPath or a list — the rule of the other path keys.
- A broker source then reads the time that the broker protocol carries: the AMQP `timestamp` property, the Kafka record timestamp, the JetStream message time. This step applies only when the source sets one of the two keys — a source that sets neither key keeps its behaviour.
- When nothing gives a time, the store uses the receipt time — a message with no stamp must still arrive.
- A value is an ISO-8601 time with an offset, or a whole number. A number below 100000000000 is seconds since the epoch, and a larger number is milliseconds — `timestamp_in_ms` of RabbitMQ and the AMQP property both fit, and no real time is ambiguous.
- A value that does not parse counts as missing, and QueueBox writes one warn line with the source — a bad stamp must not stop the source.
- `scheduled_at` is the earlier of the publish time and the database receipt time, plus the delay — a producer clock that runs ahead cannot hold a message longer than the delay.
- A source that sets either key must set an `initialDelay` above zero, or the start stops — without the delay the keys give no order and look as if they do.
- The publish time travels on `InboxMessage.publishedAt`. The store signature does not change — each consumer already builds the message.
- The relay claim orders by `scheduled_at`, then `created_at` — a push source gets the same order as a pull source. Both columns hold the receipt time for a source without the keys.
- A key header on an HTTP source follows the credential header rule of the other key headers.
- No migration and no client library change — the pull claim already orders by `scheduled_at` first.
- The docs state three limits: a message later than the delay is out of order, a pull retry lets a later message pass, and the order is only as good as the clock that wrote the stamp.

## Out
- A new order column.
- A change to the pull retry.
- Sequential stores in the RabbitMQ consumer.
- The broker time without one of the two keys.

## How I know it works
- With `scheduledAtPath: $.time` and `initialDelay: 30s`, two messages of one aggregate stored in reverse publish order leave the pull claim and the relay claim in publish order, on PostgreSQL and on SQL Server.
- A stored row has `scheduled_at` equal to the publish time plus 30 seconds.
- A publish time in the future gives `scheduled_at` equal to the receipt time plus 30 seconds.
- `timestamp_in_ms: 1790000000000`, `1790000000` and `2026-09-21T10:00:00Z` parse. `yesterday` gives the receipt time and one warn line.
- A source with `scheduledAtPath` and no `initialDelay`, or `initialDelay: 0s`, stops the start and names both keys.
- Both keys load from YAML and from `QUEUEBOX_*` variables to the same config.
- `./gradlew check detekt` passes.
