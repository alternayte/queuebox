#!/usr/bin/env bash
# The smoke test of the NATS bridge example.
#
# It publishes one message to `orders.created` and asserts that the same event reaches
# `processed.orders`, which proves the whole path: JetStream source, inbox, relay, outbox,
# JetStream destination.
set -euo pipefail

cd "$(dirname "$0")"
PROJECT="qb-example-nats"
PORT="${QUEUEBOX_PORT:-18086}"

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

echo "==> publish one order event"
docker compose -f docker-compose.yml -p "$PROJECT" run --rm --entrypoint sh streams -c \
  "nats --server nats://nats:4222 pub orders.created '{\"id\":\"nats_smoke_001\",\"type\":\"order.created\",\"total\":42}'"

echo "==> the event must reach the outgoing subject"
for _ in $(seq 1 30); do
  # `stream get --json` prints the body base64 encoded, which reads the same with no terminal.
  # `stream view` pages its output and prints nothing at all when no terminal is attached.
  out=$(docker compose -f docker-compose.yml -p "$PROJECT" run --rm --entrypoint sh streams -c \
    "nats --server nats://nats:4222 stream get PROCESSED 1 --json 2>/dev/null" || true)
  body=$(printf '%s' "$out" | sed -n 's/.*"data": "\([^"]*\)".*/\1/p' | base64 -d 2>/dev/null || true)
  if printf '%s' "$body" | grep -q "nats_smoke_001"; then
    delivered=yes
    break
  fi
  sleep 2
done
if [ "${delivered:-no}" != "yes" ]; then
  echo "FAIL: the event never reached processed.orders"
  exit 1
fi

echo "PASS: nats-bridge"
