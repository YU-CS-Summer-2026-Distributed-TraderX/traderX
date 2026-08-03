#!/usr/bin/env bash
set -euo pipefail

wait_http() {
  local url="$1" label="$2" deadline=$((SECONDS + 180))
  until curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; do
    (( SECONDS < deadline )) || { echo "[fail] timeout waiting for ${label}: ${url}"; exit 1; }
    sleep 2
  done
  echo "[ok] ${label}"
}

wait_http http://127.0.0.1:19080/healthz primary-sidecar
wait_http http://127.0.0.1:19081/healthz follower-sidecar
wait_http http://127.0.0.1:18110/actuator/health/readiness primary-readiness
wait_http http://127.0.0.1:18111/actuator/health/readiness follower-readiness

payload='{"accountId":22214,"security":"IBM","side":"Buy","quantity":7,"limitPrice":1.0}'
response="$(curl -fsS --max-time 10 -H 'Content-Type: application/json' -d "${payload}" \
  http://127.0.0.1:18110/orders)"
order_id="$(printf '%s' "${response}" | jq -r '.orderId // .id // empty')"
[[ -n "${order_id}" ]] || { echo "[fail] primary did not return an order id: ${response}"; exit 1; }

deadline=$((SECONDS + 30))
until curl -fsS --max-time 2 "http://127.0.0.1:18111/orders/${order_id}" >/dev/null 2>&1; do
  (( SECONDS < deadline )) || { echo "[fail] follower did not expose replicated ${order_id}"; exit 1; }
  sleep 1
done

echo "[done] YU11 Aeron HA compose accepted and replicated ${order_id}"
