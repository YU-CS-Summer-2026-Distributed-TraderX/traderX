#!/usr/bin/env bash
# Trigger the journaled-BLP benchmark and poll until complete.
#
# Env knobs (all optional):
#   BASE_URL          — order-matcher base URL (default: http://localhost:18110)
#   WARMUP_ORDERS     — orders for the warm-up phase (default: 250000)
#   MEASURED_ORDERS   — orders for the measured phase (default: 2000000)
#   RING_SIZE         — input ring size (default: 65536)
#   WAIT_STRATEGY     — yielding | busyspin | sleeping | blocking (default: yielding)
#   BATCH_RECORDS     — journal coalescing buffer depth in records (default: 1024)
#   SIMULATED_RTT_MS  — simulate NATS replication RTT in ms; 0 = journaling only (default: 0)
#   POLL_INTERVAL_S   — seconds between status polls (default: 5)
#
# Usage examples:
#   # Journaling only (YU02-lmax-kubernetes topology):
#   BASE_URL=http://localhost:18110 bash scripts/bench/run-journaled-blp-bench.sh
#
#   # Simulate 2ms NATS RTT (HA branch ceiling estimate):
#   SIMULATED_RTT_MS=2 bash scripts/bench/run-journaled-blp-bench.sh
#
#   # GKE cluster:
#   BASE_URL=https://yaakovseif.dev bash scripts/bench/run-journaled-blp-bench.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18110}"
WARMUP_ORDERS="${WARMUP_ORDERS:-250000}"
MEASURED_ORDERS="${MEASURED_ORDERS:-2000000}"
RING_SIZE="${RING_SIZE:-65536}"
WAIT_STRATEGY="${WAIT_STRATEGY:-yielding}"
BATCH_RECORDS="${BATCH_RECORDS:-1024}"
SIMULATED_RTT_MS="${SIMULATED_RTT_MS:-0}"
POLL_INTERVAL_S="${POLL_INTERVAL_S:-5}"

RUN_URL="${BASE_URL}/system/benchmarks/journaled-blp/run"
STATUS_URL="${BASE_URL}/system/benchmarks/journaled-blp"

echo "=== journaled-BLP benchmark ==="
echo "  target       : ${BASE_URL}"
echo "  warmupOrders : ${WARMUP_ORDERS}"
echo "  measuredOrders: ${MEASURED_ORDERS}"
echo "  ringSize     : ${RING_SIZE}"
echo "  waitStrategy : ${WAIT_STRATEGY}"
echo "  batchRecords : ${BATCH_RECORDS}"
echo "  simulatedRttMs: ${SIMULATED_RTT_MS}"
echo ""

# Trigger the run.
RESPONSE=$(curl -sf -X POST \
  "${RUN_URL}?warmupOrders=${WARMUP_ORDERS}&measuredOrders=${MEASURED_ORDERS}&ringSize=${RING_SIZE}&waitStrategy=${WAIT_STRATEGY}&batchRecords=${BATCH_RECORDS}&simulatedRttMs=${SIMULATED_RTT_MS}" \
  -H "Accept: application/json")
echo "Started: ${RESPONSE}"
echo ""

# Poll until the benchmark completes or fails.
while true; do
  STATUS=$(curl -sf "${STATUS_URL}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('phase','?'))" 2>/dev/null || echo "unknown")

  if [[ "${STATUS}" == "complete" || "${STATUS}" == "failed" ]]; then
    RESULT=$(curl -sf "${STATUS_URL}")
    PHASE=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('phase','?'))" 2>/dev/null)
    SUSTAINED=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('lastSustainedOrdersPerSecond',0))" 2>/dev/null)
    PEAK_BLP=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('blpPeak',0))" 2>/dev/null)
    PEAK_TRADES=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tradesPeak',0))" 2>/dev/null)
    DURATION=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('lastSustainedOrdersPerSecond',0))" 2>/dev/null)
    ERROR=$(echo "${RESULT}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error',''))" 2>/dev/null)

    echo "=== Result: ${PHASE} ==="
    echo "  sustained orders/sec : ${SUSTAINED}"
    echo "  peak BLP events/sec  : ${PEAK_BLP}"
    echo "  peak trades/sec      : ${PEAK_TRADES}"
    echo "  simulatedRttMs       : ${SIMULATED_RTT_MS}"
    [[ -n "${ERROR}" && "${ERROR}" != "None" ]] && echo "  error                : ${ERROR}"
    echo ""

    if [[ "${STATUS}" == "failed" ]]; then
      exit 1
    fi
    break
  fi

  echo "  phase: ${STATUS} — polling in ${POLL_INTERVAL_S}s..."
  sleep "${POLL_INTERVAL_S}"
done
