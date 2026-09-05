#!/usr/bin/env bash
# The smoke test of the change data capture example.
#
# The configuration sets a 30-second reconciliation interval and capture beats it, so a
# delivery within 15 seconds proves that capture woke delivery rather than the timer.
set -euo pipefail

cd "$(dirname "$0")"
PROJECT="qb-example-cdc"
PORT="${QUEUEBOX_PORT:-18084}"

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

echo "==> wait for capture to take its snapshot"
sleep 15

echo "==> insert one outbox row directly, as a producer does"
docker compose -f docker-compose.yml -p "$PROJECT" exec -T postgres \
  psql -U queuebox -d queuebox -c \
  "INSERT INTO outbox (topic, payload) VALUES ('order.created', '{\"id\":\"cdc_smoke_001\"}'::jsonb)"

echo "==> the delivery must arrive well inside the 30-second reconciliation interval"
for _ in $(seq 1 15); do
  hits=$(docker compose -f docker-compose.yml -p "$PROJECT" logs receiver 2>&1 | grep -c "cdc_smoke_001" || true)
  if [ "$hits" -ge 1 ]; then
    delivered=yes
    break
  fi
  sleep 1
done
if [ "${delivered:-no}" != "yes" ]; then
  echo "FAIL: capture did not wake delivery inside 15 seconds"
  exit 1
fi

echo "==> capture state survives a restart"
docker compose -f docker-compose.yml -p "$PROJECT" restart queuebox
for _ in $(seq 1 90); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/health" || true)" = "200" ]; then
    restarted=yes
    break
  fi
  sleep 2
done
if [ "${restarted:-no}" != "yes" ]; then
  echo "FAIL: the instance never became ready again"
  exit 1
fi

docker compose -f docker-compose.yml -p "$PROJECT" exec -T postgres \
  psql -U queuebox -d queuebox -c \
  "INSERT INTO outbox (topic, payload) VALUES ('order.created', '{\"id\":\"cdc_smoke_002\"}'::jsonb)"

for _ in $(seq 1 30); do
  hits=$(docker compose -f docker-compose.yml -p "$PROJECT" logs receiver 2>&1 | grep -c "cdc_smoke_002" || true)
  if [ "$hits" -ge 1 ]; then
    after_restart=yes
    break
  fi
  sleep 1
done
if [ "${after_restart:-no}" != "yes" ]; then
  echo "FAIL: capture did not deliver after the restart"
  exit 1
fi

echo "==> the distribution ships both capture connectors"
connectors=$(docker compose -f docker-compose.yml -p "$PROJECT" exec -T queuebox \
  sh -c 'ls lib | grep -c "debezium-connector-\(postgres\|sqlserver\)"' || true)
if [ "${connectors:-0}" -lt 2 ]; then
  echo "FAIL: the image must ship the PostgreSQL and the SQL Server connector, found $connectors"
  exit 1
fi

echo "==> a second process with the same capture identity must be refused"
docker compose -f docker-compose.yml -p "$PROJECT" --profile second up -d queuebox-second
for _ in $(seq 1 60); do
  refused=$(docker compose -f docker-compose.yml -p "$PROJECT" logs queuebox-second 2>&1 |
    grep -c "already has an active owner" || true)
  if [ "$refused" -ge 1 ]; then
    break
  fi
  sleep 2
done
if [ "${refused:-0}" -lt 1 ]; then
  echo "FAIL: the second instance did not report the capture owner conflict"
  docker compose -f docker-compose.yml -p "$PROJECT" logs queuebox-second 2>&1 | tail -30
  exit 1
fi

echo "==> the refused instance still delivers through SQL"
docker compose -f docker-compose.yml -p "$PROJECT" exec -T postgres \
  psql -U queuebox -d queuebox -c \
  "INSERT INTO outbox (topic, payload) VALUES ('order.created', '{\"id\":\"cdc_smoke_003\"}'::jsonb)"
for _ in $(seq 1 60); do
  hits=$(docker compose -f docker-compose.yml -p "$PROJECT" logs receiver 2>&1 | grep -c "cdc_smoke_003" || true)
  if [ "$hits" -ge 1 ]; then
    still_delivering=yes
    break
  fi
  sleep 1
done
if [ "${still_delivering:-no}" != "yes" ]; then
  echo "FAIL: delivery stopped while capture ownership was refused"
  exit 1
fi

echo "PASS: cdc"
