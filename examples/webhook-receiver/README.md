# Example: a webhook receiver

A webhook arrives at QueueBox. QueueBox deduplicates it, stores it, and delivers it to an HTTP
service. Nothing in this example needs an account.

## What the example shows

- An inbox source that accepts a webhook and takes the idempotency key from the body.
- Deduplication. The same webhook twice stores one row.
- The inbox relay, which moves the row to the outbox.
- An HTTP destination that receives the delivery.

## Run it

```bash
./smoke-test.sh
```

The script starts the stack, waits for readiness, posts the same webhook twice, and asserts that
the first request answers `202` and the second answers `200`.

To run it by hand:

```bash
docker compose up -d --build
curl http://localhost:18080/health
curl -X POST http://localhost:18080/inbox/stripe \
  -H 'Content-Type: application/json' \
  -d '{"id":"evt_1","type":"payment.succeeded","amount":4200}'
docker compose logs receiver
docker compose down -v
```

`docker compose logs receiver` shows the delivered body, which proves the end to end path.

## Files

| File | What it holds |
|------|---------------|
| `queuebox.yml` | The source, the destination and the route. |
| `docker-compose.yml` | QueueBox, PostgreSQL and the echo destination. |
| `smoke-test.sh` | The assertion that the example works. |
