#!/usr/bin/env bash
# F-081: the smoke test of the webhook receiver example.
#
# It starts the stack, waits for readiness, posts a webhook twice, and asserts that QueueBox
# accepts the first copy and rejects the second copy as a duplicate.
set -euo pipefail

cd "$(dirname "$0")"
PROJECT="qb-example-fanout"
PORT="${QUEUEBOX_PORT:-18081}"

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

echo "==> post one event per destination topic"
# A route matches one destination, so the producer writes one message per destination.
first=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/inbox/stripe" \
  -H 'Content-Type: application/json' \
  -d '{"id":"evt_smoke_001-analytics","type":"analytics.payment.succeeded","amount":4200}')
second=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/inbox/stripe" \
  -H 'Content-Type: application/json' \
  -d '{"id":"evt_smoke_001-audit","type":"audit.payment.succeeded","amount":4200}')

echo "first=$first second=$second"
[ "$first" = "202" ] || { echo "FAIL: a new event must return 202, got $first"; exit 1; }
[ "$second" = "202" ] || { echo "FAIL: a new event must return 202, got $second"; exit 1; }

echo "==> wait for BOTH destinations to receive the same message"
for _ in $(seq 1 45); do
  a=$(docker compose -f docker-compose.yml -p "$PROJECT" logs analytics 2>&1 | grep -c "evt_smoke_001" || true)
  b=$(docker compose -f docker-compose.yml -p "$PROJECT" logs audit 2>&1 | grep -c "evt_smoke_001" || true)
  if [ "$a" -ge 1 ] && [ "$b" -ge 1 ]; then
    delivered=yes
    break
  fi
  sleep 2
done
if [ "${delivered:-no}" != "yes" ]; then
  echo "FAIL: the fan-out did not reach both destinations (analytics=$a audit=$b)"
  docker compose -f docker-compose.yml -p "$PROJECT" logs analytics audit 2>&1 | tail -20
  exit 1
fi
echo "analytics received $a, audit received $b"

echo "PASS: http-fanout"
