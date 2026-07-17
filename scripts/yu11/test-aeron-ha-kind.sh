#!/usr/bin/env bash
set -euo pipefail

CONTEXT="kind-traderx-yu11-aeron"
NAMESPACE="traderx"
PORT_FORWARD_LOG="/private/tmp/traderx-yu11-port-forward.log"
pf_pid=""

now_ms() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time * 1000'
}

stop_port_forward() {
  if [[ -n "${pf_pid}" ]]; then
    kill "${pf_pid}" 2>/dev/null || true
    wait "${pf_pid}" 2>/dev/null || true
    pf_pid=""
  fi
}

start_port_forward() {
  stop_port_forward
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" port-forward \
    service/order-matcher-primary 18110:18110 >"${PORT_FORWARD_LOG}" 2>&1 &
  pf_pid=$!

  local deadline=$((SECONDS + 30))
  until curl -fsS --max-time 2 http://127.0.0.1:18110/actuator/health/readiness >/dev/null 2>&1; do
    (( SECONDS < deadline )) || { cat "${PORT_FORWARD_LOG}"; exit 1; }
    sleep 1
  done
}

submit_order() {
  local response
  response="$(curl -fsS --max-time 10 -H 'Content-Type: application/json' \
    -d '{"accountId":22214,"security":"IBM","side":"Buy","quantity":7,"limitPrice":200.0}' \
    http://127.0.0.1:18110/orders)"
  printf '%s' "${response}" | jq -e '.orderId // .id' >/dev/null
}

trap stop_port_forward EXIT

kubectl --context "${CONTEXT}" -n "${NAMESPACE}" get pod order-matcher-0 order-matcher-1
kubectl --context "${CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready \
  pod/order-matcher-0 pod/order-matcher-1 --timeout=10m

start_port_forward
submit_order
stop_port_forward

primary="$(kubectl --context "${CONTEXT}" -n "${NAMESPACE}" get pods \
  -l app=order-matcher,blp-role=primary -o jsonpath='{.items[0].metadata.name}')"
[[ -n "${primary}" ]] || { echo "[error] no elected primary"; exit 1; }

start_ms="$(now_ms)"
# Model a process/node crash, not a graceful rollout. A normal pod deletion leaves the
# old holder in Terminating and intentionally exercises LeaderElection's safety guard.
kubectl --context "${CONTEXT}" -n "${NAMESPACE}" delete pod "${primary}" \
  --grace-period=0 --force --wait=false
deadline_ms=$((start_ms + 10000))
promoted=""
while (( $(now_ms) < deadline_ms )); do
  promoted="$(kubectl --context "${CONTEXT}" -n "${NAMESPACE}" get pods \
    -l app=order-matcher,blp-role=primary -o jsonpath='{.items[0].metadata.name}' \
    2>/dev/null || true)"
  if [[ -n "${promoted}" && "${promoted}" != "${primary}" ]]; then
    break
  fi
  sleep 0.05
done
[[ -n "${promoted}" && "${promoted}" != "${primary}" ]] || {
  echo "[error] alternate pod did not become primary within 10 seconds"
  exit 1
}
promoted_ms="$(now_ms)"
failover_ms=$((promoted_ms - start_ms))
(( failover_ms <= 3000 )) || {
  echo "[error] default Lease failover ${failover_ms}ms exceeds the 3000ms gate"
  exit 1
}
echo "[ok] default Lease failover ${primary} -> ${promoted} in ${failover_ms}ms"

kubectl --context "${CONTEXT}" -n "${NAMESPACE}" wait --for=condition=Ready \
  pod/order-matcher-0 pod/order-matcher-1 --timeout=10m
start_port_forward
submit_order

for pod in order-matcher-0 order-matcher-1; do
  kubectl --context "${CONTEXT}" -n "${NAMESPACE}" exec "${pod}" \
    -c aeron-replication-sidecar -- sh -c 'test -d /var/lib/traderx-lmax/aeron-archive'
  if kubectl --context "${CONTEXT}" -n "${NAMESPACE}" logs "${pod}" -c order-matcher \
    | grep -Eq 'Aeron follower protocol fault|Aeron Archive replay-to-live merge failed|clashing sessionId'; then
    echo "[error] ${pod} logged an Aeron protocol or Archive merge fault"
    exit 1
  fi
done
echo "[done] dedicated YU11 kind pair accepts orders before/after <=3s Lease failover and rejoins cleanly"
