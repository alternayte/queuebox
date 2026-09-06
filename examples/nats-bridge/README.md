# NATS bridge

A NATS JetStream subject in, a JetStream subject out, with the QueueBox inbox and outbox in
between. The application writes no broker code and creates no tables.

```bash
./smoke-test.sh
```

The stack consumes `orders.>` from the stream `ORDERS`, stores each message in the inbox,
forwards it to the outbox, and publishes it to `processed.orders` in the stream `PROCESSED`.

What the example demonstrates:

- **The stream is created by the operator, not by QueueBox.** The `streams` service creates both
  before QueueBox starts, because the retention and the replication of a stream are decisions
  QueueBox must not make for you.
- **The acknowledgement follows the inbox row.** A failed store is negatively acknowledged and
  JetStream returns the message at once; a crash leaves it unacknowledged and JetStream returns
  it after the acknowledgement wait.
- **The source is JetStream only.** Core NATS can acknowledge nothing, so an inbox on it would
  lose every message that arrives while QueueBox restarts.

See [docs/delivery-semantics.md](../../docs/delivery-semantics.md) for the full contract.
