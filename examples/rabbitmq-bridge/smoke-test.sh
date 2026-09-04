#!/usr/bin/env bash
# F-081: the smoke test of the RabbitMQ bridge example.
#
# The queue binds on `payment.*`, not on `#`, so the message arrives only when QueueBox renders
# the routing key from the topic. A missing routingKeyTemplate therefore fails this test.
# The test also reads the body back, so an empty or wrong payload fails.
set -euo pipefail

cd "$(dirname "$0")"
PROJECT="qb-example-rabbit"
PORT="${QUEUEBOX_PORT:-18082}"

cleanup() {
  docker compose -f docker-compose.yml -p "$PROJECT" logs queuebox 2>&1 | tail -40 || true
  docker compose -f docker-compose.yml -p "$PROJECT" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> start the stack"
QUEUEBOX_PORT="$PORT" docker compose -f docker-compose.yml -p "$PROJECT" up -d --build

echo "==> wait for readiness"
for _ in $(seq 1 90); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/health" || true)" = "200" ]; then
    ready=yes
    break
  fi
  sleep 2
done
if [ "${ready:-no}" != "yes" ]; then
  echo "FAIL: the instance never became ready"
  exit 1
fi

echo "==> post the webhook"
body='{"id":"evt_smoke_001","type":"payment.succeeded","amount":4200}'
first=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/inbox/stripe" \
  -H 'Content-Type: application/json' -d "$body")
second=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/inbox/stripe" \
  -H 'Content-Type: application/json' -d "$body")

echo "first=$first second=$second"
[ "$first" = "202" ] || { echo "FAIL: a new webhook must return 202, got $first"; exit 1; }
[ "$second" = "200" ] || { echo "FAIL: a duplicate webhook must return 200, got $second"; exit 1; }

echo "==> wait for the message to reach the RabbitMQ queue"
for _ in $(seq 1 45); do
  depth=$(docker compose -f docker-compose.yml -p "$PROJECT" exec -T rabbitmq \
    rabbitmqctl list_queues name messages --no-table-headers 2>/dev/null \
    | awk '$1 == "events-audit" { print $2 }')
  if [ -n "${depth:-}" ] && [ "$depth" -ge 1 ]; then
    delivered=yes
    break
  fi
  sleep 2
done
if [ "${delivered:-no}" != "yes" ]; then
  echo "FAIL: the exchange never delivered the message to the bound queue"
  docker compose -f docker-compose.yml -p "$PROJECT" exec -T rabbitmq rabbitmqctl list_queues || true
  exit 1
fi
echo "the queue events-audit holds $depth message(s)"

echo "==> read the message back and check the routing key and the body"
payload=$(docker compose -f docker-compose.yml -p "$PROJECT" exec -T rabbitmq \
  rabbitmqadmin --username=guest --password=guest get queue=events-audit ackmode=ack_requeue_false 2>&1 || true)
echo "$payload" | head -20
echo "$payload" | grep -q "evt_smoke_001" || {
  echo "FAIL: the delivered body does not carry the message"; exit 1; }
echo "$payload" | grep -q "payment.succeeded" || {
  echo "FAIL: the routing key is not the topic"; exit 1; }

echo "PASS: rabbitmq-bridge"
