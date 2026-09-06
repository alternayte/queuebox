# Examples

Each directory holds a runnable example: a `queuebox.yml`, a `docker-compose.yml`, a `README.md`
and a `smoke-test.sh` that asserts the example works. The `examples` job of
`.github/workflows/ci.yml` runs every smoke test.

| Example | What it shows |
|---------|---------------|
| [webhook-receiver](webhook-receiver) | A webhook arrives, QueueBox deduplicates it, and an HTTP service receives it. |
| [http-fanout](http-fanout) | One producer, two HTTP consumers, each with its own retries. |
| [rabbitmq-bridge](rabbitmq-bridge) | An HTTP producer and an AMQP consumer, with the outbox in between. |
| [kafka-bridge](kafka-bridge) | A Kafka topic in, a Kafka topic out, with the inbox and the outbox between. |
| [nats-bridge](nats-bridge) | A NATS JetStream subject in, a JetStream subject out. |
| [cdc](cdc) | Embedded change data capture wakes delivery before the reconciliation timer. |
| [pull](pull) | A pull source, and the claim, renewal and completion SQL that a worker runs. |

`queuebox.yml` in this directory is not an example. `docker-compose.yml` at the repository root
mounts it, and the quick start in `README.md` uses it.

## Run every example

```bash
for example in webhook-receiver http-fanout rabbitmq-bridge cdc kafka-bridge nats-bridge; do
  ./"$example"/smoke-test.sh
done
```

Each example publishes QueueBox on its own port, so two examples never collide.

`pull` holds no Compose stack and no smoke test. It is the SQL contract of a pull worker,
which an application runs against its own database. The automated proof of that contract
is `PullLeaseTest` in the `postgres` and `sqlserver` modules, which executes these files.
