#!/usr/bin/env bash
# Environment-agnostic in-cluster benchmark for a GKE order-matcher deployment (HA StatefulSet or
# single-BLP Deployment — both expose the same order-matcher Service DNS). All load and all metric
# reads run from an in-cluster bench-runner pod against the order-matcher ClusterIP, so there is no
# kubectl port-forward tunnel to distort results.
#
# Metrics (no pod restart, no direct DB access needed):
#   booked/s = delta traderx_order_events_total{event=fill|partial_fill|force_fill} / elapsed
#              (== DB persist rate while traderx_projector_lag_seq stays 0)
#   submit/s = batch-load accepted orders / elapsed
#   peak/s   = traderx_trades_per_second_peak (cumulative high-water mark; informational)
#
# Usage: bash run-gke-bench.sh <label> [runs] [secs] [batch] [conc]
set -euo pipefail
LABEL="${1:-gke}"
RUNS="${2:-3}"
SECS="${3:-30}"
BATCH="${4:-1000}"
CONC="${5:-48}"
CTX="${KUBE_CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NAMESPACE:-traderx}"
SVC="${MATCHER_SVC:-http://order-matcher.${NS}.svc.cluster.local:18110}"
RESULTS="$(dirname "$0")/results/gke-comparison.csv"

read_fills() {
  kubectl --context "$CTX" exec bench-runner -n "$NS" -- node -e "
    fetch('${SVC}/metrics').then(r=>r.text()).then(t=>{
      let s=0; for(const m of t.matchAll(/traderx_order_events_total\{event=\"(?:fill|partial_fill|force_fill)\"\} ([0-9.]+)/g)) s+=parseFloat(m[1]);
      console.log(Math.round(s));
    }).catch(()=>console.log('ERR'))" 2>/dev/null
}
read_peak() {
  kubectl --context "$CTX" exec bench-runner -n "$NS" -- node -e "
    fetch('${SVC}/metrics').then(r=>r.text()).then(t=>{
      console.log((t.match(/traderx_trades_per_second_peak ([0-9.]+)/)||[0,'0'])[1]);
    }).catch(()=>console.log('ERR'))" 2>/dev/null
}

echo "=== [${LABEL}] GKE in-cluster benchmark: --batch ${BATCH} --conc ${CONC} --secs ${SECS}, ${RUNS} runs (no reset) ==="
printf "%4s  %10s  %10s  %10s  %8s\n" "run" "booked/s" "submit/s" "peak/s" "failed"
echo "-------------------------------------------------------"
for i in $(seq 1 "$RUNS"); do
  F0=$(read_fills)
  T0=$(date +%s.%N)
  OUT=$(kubectl --context "$CTX" exec bench-runner -n "$NS" -- \
    env "MATCHER_URL=${SVC}" "SIDES=${SIDES:-}" "ACCOUNT=${ACCOUNT:-42422}" "LIMIT=${LIMIT:-1000000}" node /batch-load.mjs --batch "$BATCH" --conc "$CONC" --secs "$SECS" 2>&1) || true
  T1=$(date +%s.%N)
  F1=$(read_fills)
  PEAK=$(read_peak)
  ELAPSED=$(echo "$T1 - $T0" | bc)
  SUBMITTED=$(echo "$OUT" | grep -o "submitted=[0-9]*" | tail -1 | cut -d= -f2); SUBMITTED="${SUBMITTED:-0}"
  FAILED=$(echo "$OUT" | grep -o "failed=[0-9]*" | tail -1 | cut -d= -f2); FAILED="${FAILED:-0}"
  BOOKED=$(echo "scale=0; ($F1 - $F0) / $ELAPSED" | bc)
  SUBMIT=$(echo "scale=0; $SUBMITTED / $ELAPSED" | bc)
  printf "%4s  %10s  %10s  %10s  %8s\n" "$i" "$BOOKED" "$SUBMIT" "$PEAK" "$FAILED"
  echo "${LABEL},run${i},${BOOKED},${SUBMIT},${PEAK},${FAILED}" >> "$RESULTS"
  sleep 3
done
