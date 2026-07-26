#!/usr/bin/env bash
# Measures trade-processor's sustained DB persist rate (rows/sec into TRADES), independent of
# the BLP's own throughput. NATS decouples order-matcher from trade-processor, so avg-max-load.mjs
# (which reads order-matcher's own counters) cannot see trade-processor's DB write speed.
#
# Protocol:
#   1. snapshot TRADES row count (N0) and timestamp (t0)
#   2. fire a load burst at order-matcher to build up backlog in NATS -> trade-processor
#   3. poll TRADES row count until it stops growing (drained) or timeout
#   4. rate = (N_final - N0) / (t_drained - t0)
#
# Usage: bash scripts/bench/measure-trade-processor-db-rate.sh [label]

set -euo pipefail
LABEL="${1:-run}"
CTX="${KUBE_CTX:-kind-traderx-state-014}"
NS="${NAMESPACE:-traderx}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
BURST_SECS="${BURST_SECS:-20}"
BURST_CONC="${BURST_CONC:-128}"
DRAIN_TIMEOUT_SECS="${DRAIN_TIMEOUT_SECS:-60}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

db_count() {
  kubectl --context "${CTX}" exec -n "${NS}" deploy/database -- \
    mariadb -utraderx -ptraderx traderx -N -e "SELECT COUNT(*) FROM TRADES;" 2>/dev/null
}

echo "=== [${LABEL}] trade-processor DB persist rate measurement ==="

N0=$(db_count)
T0=$(date +%s.%N)
echo "  baseline TRADES rows: ${N0}"

echo "  firing load burst (conc=${BURST_CONC}, secs=${BURST_SECS})..."
MATCHER_URL="${MATCHER_URL}" node "${SCRIPT_DIR}/max-load.mjs" --conc "${BURST_CONC}" --secs "${BURST_SECS}" > /tmp/max-load-${LABEL}.log 2>&1 || true

echo "  burst done, polling TRADES table until it stops growing..."
LAST=$(db_count)
STABLE_COUNT=0
DEADLINE=$(echo "${T0} + ${DRAIN_TIMEOUT_SECS}" | bc)
while true; do
  sleep 1
  NOW=$(date +%s.%N)
  CUR=$(db_count)
  if [[ "${CUR}" == "${LAST}" ]]; then
    STABLE_COUNT=$((STABLE_COUNT + 1))
  else
    STABLE_COUNT=0
  fi
  LAST="${CUR}"
  if [[ "${STABLE_COUNT}" -ge 3 ]]; then
    break
  fi
  if (( $(echo "${NOW} > ${DEADLINE}" | bc -l) )); then
    echo "  [warn] drain timeout reached at ${DRAIN_TIMEOUT_SECS}s, using current count"
    break
  fi
done

N1=$(db_count)
T1=$(date +%s.%N)
ELAPSED=$(echo "${T1} - ${T0}" | bc)
DELTA=$((N1 - N0))
RATE=$(echo "scale=1; ${DELTA} / ${ELAPSED}" | bc)

echo ""
echo "  [${LABEL}] TRADES delta      : ${DELTA} rows"
echo "  [${LABEL}] elapsed (t0->drain): ${ELAPSED}s"
echo "  [${LABEL}] persist rate      : ${RATE} rows/sec"
echo ""
echo "${LABEL},${DELTA},${ELAPSED},${RATE}" >> "${SCRIPT_DIR}/results/db-rate-comparison.csv"
