#!/usr/bin/env bash
# The smoke test of the Kafka bridge example.
#
# It produces one record to `orders` and asserts that the same event reaches `orders-processed`,
# which proves the whole path: Kafka source, inbox, relay, outbox, Kafka destination.
set -euo pipefail

cd "$(dirname "$0")"
PROJECT="qb-example-kafka"
PORT="${QUEUEBOX_PORT:-18085}"

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

echo "==> produce one order event"
docker compose -f docker-compose.yml -p "$PROJECT" exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic orders \
  <<< '{"id":"kafka_smoke_001","type":"order.created","total":42}'

echo "==> the event must reach the outgoing topic"
for _ in $(seq 1 30); do
  out=$(docker compose -f docker-compose.yml -p "$PROJECT" exec -T kafka \
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
    --topic orders-processed --from-beginning --timeout-ms 4000 2>/dev/null || true)
  if echo "$out" | grep -q "kafka_smoke_001"; then
    delivered=yes
    break
  fi
  sleep 2
done
if [ "${delivered:-no}" != "yes" ]; then
  echo "FAIL: the event never reached orders-processed"
  exit 1
fi

echo "PASS: kafka-bridge"
