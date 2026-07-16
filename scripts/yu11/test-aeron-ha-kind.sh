#!/usr/bin/env bash
set -euo pipefail

CONTEXT="kind-traderx-yu11-aeron"
NAMESPACE="traderx"
PORT_FORWARD_LOG="/private/tmp/traderx-yu11-port-forward.log"

kubectl --context "${CONTEXT}" -n "${NAMESPACE}" get pod order-matcher-0 order-matcher-1
kubectl --context "${CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready \
  pod/order-matcher-0 pod/order-matcher-1 --timeout=10m

kubectl --context "${CONTEXT}" -n "${NAMESPACE}" port-forward \
  service/order-matcher-primary 18110:18110 >"${PORT_FORWARD_LOG}" 2>&1 &
pf_pid=$!
trap 'kill "${pf_pid}" 2>/dev/null || true' EXIT

deadline=$((SECONDS + 30))
until curl -fsS --max-time 2 http://127.0.0.1:18110/actuator/health/readiness >/dev/null 2>&1; do
  (( SECONDS < deadline )) || { cat "${PORT_FORWARD_LOG}"; exit 1; }
  sleep 1
done

response="$(curl -fsS --max-time 10 -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"security":"IBM","side":"Buy","quantity":7,"limitPrice":1.0}' \
  http://127.0.0.1:18110/orders)"
printf '%s' "${response}" | jq -e '.orderId // .id' >/dev/null

kubectl --context "${CONTEXT}" -n "${NAMESPACE}" exec order-matcher-1 \
  -c aeron-replication-sidecar -- sh -c 'test -d /var/lib/traderx-lmax/aeron-archive'
echo "[done] dedicated YU11 kind pair is ready and accepts an order through the primary service"
