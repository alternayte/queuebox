# Examples

Each directory holds a runnable example: a `queuebox.yml`, a `docker-compose.yml`, a `README.md`
and a `smoke-test.sh` that asserts the example works. The `examples` job of
`.github/workflows/ci.yml` runs every smoke test.

| Example | What it shows |
|---------|---------------|
| [webhook-receiver](webhook-receiver) | A webhook arrives, QueueBox deduplicates it, and an HTTP service receives it. |
| [http-fanout](http-fanout) | One producer, two HTTP consumers, each with its own retries. |
| [rabbitmq-bridge](rabbitmq-bridge) | An HTTP producer and an AMQP consumer, with the outbox in between. |

`queuebox.yml` in this directory is not an example. `docker-compose.yml` at the repository root
mounts it, and the quick start in `README.md` uses it.

## Run every example

```bash
for example in webhook-receiver http-fanout rabbitmq-bridge; do
  ./"$example"/smoke-test.sh
done
```

Each example publishes QueueBox on its own port, so two examples never collide.
