#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/generated/code/target-generated/YU11-aeron-replication/runtime/docker-compose.aeron-ha.yml"

wait_http() {
  local url="$1" label="$2" timeout_seconds="${3:-180}"
  local deadline=$((SECONDS + timeout_seconds))
  until curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; do
    (( SECONDS < deadline )) || {
      echo "[fail] timeout waiting for ${label}: ${url}"
      return 1
    }
    sleep 1
  done
  echo "[ok] ${label}"
}

post_order() {
  local quantity="$1"
  curl -fsS --max-time 10 -H 'Content-Type: application/json' \
    -d "{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":${quantity},\"limitPrice\":1.0}" \
    http://127.0.0.1:18110/orders | jq -er '.orderId'
}

wait_order() {
  local order_id="$1" deadline=$((SECONDS + 60))
  until curl -fsS --max-time 2 "http://127.0.0.1:18111/orders/${order_id}" >/dev/null 2>&1; do
    (( SECONDS < deadline )) || {
      echo "[fail] follower did not expose ${order_id}"
      return 1
    }
    sleep 1
  done
  echo "[ok] follower exposes ${order_id}"
}

wait_http http://127.0.0.1:18110/actuator/health/readiness primary-readiness
wait_http http://127.0.0.1:18111/actuator/health/readiness follower-readiness

docker compose -f "${COMPOSE_FILE}" stop order-matcher-follower aeron-follower

offline_ids=()
for quantity in 21 22 23; do
  offline_ids+=("$(post_order "${quantity}")")
done
echo "[ok] primary accepted offline gap: ${offline_ids[*]}"

# Aeron Archive's mark-file liveness timeout is ten seconds. Wait it out before restarting the
# same persistent sidecar so a normal stop cannot be mistaken for a second active Archive.
sleep 11
docker compose -f "${COMPOSE_FILE}" start aeron-follower
wait_http http://127.0.0.1:19081/healthz follower-sidecar
docker compose -f "${COMPOSE_FILE}" start order-matcher-follower
wait_http http://127.0.0.1:18111/actuator/health/readiness follower-replay-readiness

for order_id in "${offline_ids[@]}"; do
  wait_order "${order_id}"
done

live_id="$(post_order 24)"
wait_order "${live_id}"
wait_http http://127.0.0.1:18111/actuator/health/readiness follower-post-replay-readiness 30

echo "[done] YU11 retained and replayed the offline gap, then replicated live ${live_id}"
