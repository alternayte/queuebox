# Example: a RabbitMQ bridge

An HTTP producer on one side, an AMQP consumer on the other. QueueBox sits between them, so a
broker outage delays a message but never loses one.

## What the example shows

- An inbox source that accepts an HTTP webhook.
- A RabbitMQ destination that publishes to a topic exchange.
- A routing key built from the topic with `routingKeyTemplate`.
- The outbox as the buffer. Stop the broker, post a message, start the broker again, and the
  message arrives.

`definitions.json` declares the exchange `queuebox-events`, the queue `events-audit` and a binding
on `payment.*`, so the example needs no setup step. The binding is deliberately narrow: it proves
that QueueBox renders the routing key from the topic. A binding on `#` would match whatever the
publisher sent, so it would test nothing. An event whose topic does not match `payment.*` reaches
the exchange and the broker drops it, because no queue is bound to it.

## Run it

```bash
./smoke-test.sh
```

The script posts a webhook and asserts that the bound queue holds the message.

To run it by hand:

```bash
docker compose up -d --build
curl -X POST http://localhost:18082/inbox/stripe -H 'Content-Type: application/json' \
  -d '{"id":"evt_1","type":"payment.succeeded"}'
docker compose exec rabbitmq rabbitmqctl list_queues
docker compose down -v
```

The management interface is not published. Add a port mapping for `15672` to open it.

## Files

| File | What it holds |
|------|---------------|
| `queuebox.yml` | The source, the RabbitMQ destination and the route. |
| `docker-compose.yml` | QueueBox, PostgreSQL and RabbitMQ. |
| `definitions.json` | The exchange, the queue and the binding. |
| `smoke-test.sh` | The assertion that the message reaches the queue. |
