# Example: an HTTP fan-out

One producer, two HTTP consumers. The producer writes one message per consumer, and each message
carries its own retries and its own dead-letter state. QueueBox does not copy one message to two
destinations. The next section states the rule.

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

The script posts one event per destination topic. It then asserts that each consumer received its
own event, and that neither consumer received the other topic. Swap the two destinations in
`queuebox.yml` and the smoke test fails.

To run it by hand from this checkout, which builds the image from the source tree:

```bash
docker compose -f docker-compose.yml up -d --build
curl -X POST http://localhost:18081/inbox/stripe -H 'Content-Type: application/json' \
  -d '{"id":"e1","type":"analytics.payment.succeeded"}'
curl -X POST http://localhost:18081/inbox/stripe -H 'Content-Type: application/json' \
  -d '{"id":"e2","type":"audit.payment.succeeded"}'
docker compose -f docker-compose.yml logs analytics audit
docker compose -f docker-compose.yml down -v
```

`docker-compose.image.yml` runs the published image instead, and needs no checkout. The
[Fan out over HTTP](https://queuebox-docs.pages.dev/how-to/fan-out-over-http/#run-the-example) guide gives
that flow.

## Files

| File | What it holds |
|------|---------------|
| `queuebox.yml` | Two destinations and two routes, one topic prefix each. |
| `docker-compose.yml` | QueueBox built from the source tree, PostgreSQL and the two consumers. |
| `docker-compose.image.yml` | The same stack on the published image. No build. |
| `smoke-test.sh` | The assertion that both consumers receive. |
