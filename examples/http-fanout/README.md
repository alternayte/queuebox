# Example: an HTTP fan-out

One producer, two HTTP consumers. Each consumer receives its own copy, with its own retries and
its own dead-letter state.

## How fan-out works in QueueBox

A route matches ONE destination. `MessageRouter` takes the first route whose pattern matches the
topic, so two routes with the same pattern do not duplicate a message.

Fan-out therefore means one outbox row per destination. The producer writes one row per topic.
That is deliberate. Each row carries its own attempt count, so a destination that is down retries
alone and never blocks the other destination.

This example gives each destination its own topic prefix, `analytics.**` and `audit.**`.

## Run it

```bash
./smoke-test.sh
```

The script posts one event per destination topic, then asserts that both consumers received one
message each.

To run it by hand:

```bash
docker compose up -d --build
curl -X POST http://localhost:18081/inbox/stripe -H 'Content-Type: application/json' \
  -d '{"id":"e1","type":"analytics.payment.succeeded"}'
curl -X POST http://localhost:18081/inbox/stripe -H 'Content-Type: application/json' \
  -d '{"id":"e2","type":"audit.payment.succeeded"}'
docker compose logs analytics audit
docker compose down -v
```

## Files

| File | What it holds |
|------|---------------|
| `queuebox.yml` | Two destinations and two routes, one topic prefix each. |
| `docker-compose.yml` | QueueBox, PostgreSQL and the two consumers. |
| `smoke-test.sh` | The assertion that both consumers receive. |
