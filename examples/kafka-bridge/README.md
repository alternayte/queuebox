# Kafka bridge

A Kafka topic in, a Kafka topic out, with the QueueBox inbox and outbox in between. The
application writes no broker code and creates no tables.

```bash
./smoke-test.sh
```

The stack consumes `orders`, stores each record in the inbox, forwards it to the outbox, and
publishes it to `orders-processed`.

What the example demonstrates:

- **The offset is committed after the inbox row commits.** A crash replays the record, and the
  unique constraint on `(source, idempotency_key)` rejects the repeat, so the inbox holds one row.
- **The consumer group is shared.** Two QueueBox replicas with the same `groupId` share the
  partitions rather than consume everything twice.
- **The publish waits for the broker.** The outbox marks a row sent only after every in-sync
  replica holds the record.

See [docs/delivery-semantics.md](../../docs/delivery-semantics.md) for the full contract and
[docs/configuration.md](../../docs/configuration.md) for every setting.
