#!/usr/bin/env bash
# Runs the teammate's exact avg-max-load methodology (--batch 1000 --conc 48 --secs 30, cold
# restart per run) but generates load FROM INSIDE the cluster against order-matcher's ClusterIP,
# instead of through kubectl port-forward from the host. Removes the port-forward tunnel as a
# confound (kubectl port-forward is known to be unreliable at high concurrency, unlike a real
# pod-to-pod or docker-compose localhost path).
set -euo pipefail
CTX="${KUBE_CTX:-kind-traderx-state-014}"
NS="${NAMESPACE:-traderx}"
RUNS="${RUNS:-3}"
SECS="${SECS:-30}"
BATCH="${BATCH:-1000}"
CONC="${CONC:-48}"
CLUSTER_URL="http://order-matcher.${NS}.svc.cluster.local:18110"
LOCAL_URL="http://localhost:18110"   # for low-volume /health and /metrics reads via port-forward

fetch_metric() {
  curl -sf "${LOCAL_URL}/metrics" 2>/dev/null | grep -m1 "^$1 " | awk '{print $2}'
}
fill_total() {
  curl -sf "${LOCAL_URL}/metrics" 2>/dev/null | awk '/^traderx_order_events_total\{event="(fill|partial_fill|force_fill)"\}/ {s+=$2} END {print s+0}'
}
wait_health() {
  for i in $(seq 1 60); do
    if curl -sf "${LOCAL_URL}/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}
wait_price_ready() {
  for i in $(seq 1 30); do
    TICKS=$(curl -sf "${LOCAL_URL}/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('matcher',{}).get('ticks',0))" 2>/dev/null || echo 0)
    if [[ "${TICKS}" -gt 0 ]]; then return 0; fi
    sleep 1
  done
  return 1
}
reset_matcher() {
  kubectl --context "${CTX}" rollout restart deployment/order-matcher -n "${NS}" >/dev/null
  kubectl --context "${CTX}" rollout status deployment/order-matcher -n "${NS}" --timeout=90s >/dev/null
  kill "$(cat /tmp/pf-order-matcher.pid 2>/dev/null)" 2>/dev/null || true
  kubectl --context "${CTX}" port-forward svc/order-matcher 18110:18110 -n "${NS}" > /tmp/pf-order-matcher.log 2>&1 &
  echo $! > /tmp/pf-order-matcher.pid
  sleep 2
  wait_health
  wait_price_ready
}

echo "=== in-cluster comparable benchmark: --batch ${BATCH} --conc ${CONC} --secs ${SECS}, ${RUNS} cold runs ==="
echo ""
printf "%4s  %10s  %10s  %10s  %8s\n" "run" "peak/s" "booked/s" "submit/s" "failed"
echo "----------------------------------------------------"

PEAKS=(); BOOKEDS=(); SUBMITS=()
for i in $(seq 1 "${RUNS}"); do
  reset_matcher
  FILLS0=$(fill_total)
  T0=$(date +%s.%N)

  OUT=$(kubectl --context "${CTX}" exec bench-runner -n "${NS}" -- \
    env "MATCHER_URL=${CLUSTER_URL}" node /bench/batch-load.mjs --batch "${BATCH}" --conc "${CONC}" --secs "${SECS}" 2>&1) || true

  T1=$(date +%s.%N)
  ELAPSED=$(echo "${T1} - ${T0}" | bc)
  SUBMITTED=$(echo "${OUT}" | grep -o "submitted=[0-9]*" | tail -1 | cut -d= -f2)
  FAILED=$(echo "${OUT}" | grep -o "failed=[0-9]*" | tail -1 | cut -d= -f2)
  SUBMITTED="${SUBMITTED:-0}"; FAILED="${FAILED:-0}"

  sleep 1
  PEAK=$(fetch_metric traderx_trades_per_second_peak)
  FILLS1=$(fill_total)
  BOOKED=$(echo "scale=1; (${FILLS1} - ${FILLS0}) / ${ELAPSED}" | bc)
  SUBMIT_RATE=$(echo "scale=1; ${SUBMITTED} / ${ELAPSED}" | bc)

  printf "%4s  %10s  %10s  %10s  %8s\n" "${i}" "${PEAK:-n/a}" "${BOOKED}" "${SUBMIT_RATE}" "${FAILED}"
  PEAKS+=("${PEAK:-0}"); BOOKEDS+=("${BOOKED}"); SUBMITS+=("${SUBMIT_RATE}")
  echo "incluster-run${i},${PEAK:-0},${BOOKED},${SUBMIT_RATE},${FAILED}" >> "$(dirname "$0")/results/incluster-comparable.csv"
  sleep 4
done

echo "----------------------------------------------------"
echo "raw peaks: ${PEAKS[*]}"
echo "raw bookeds: ${BOOKEDS[*]}"
echo "raw submits: ${SUBMITS[*]}"
