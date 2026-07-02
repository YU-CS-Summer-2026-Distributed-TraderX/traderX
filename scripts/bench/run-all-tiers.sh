#!/usr/bin/env bash
# Run the full throughput-test ladder and write a comparison report.
#
# Tiers:
#   1. E2E throughput        — avg-max-load.mjs (BLP + journaler + projector + DB)
#   2. Journaled-BLP alone   — POST /system/benchmarks/journaled-blp/run (simulatedRttMs=0)
#   3. Journaled + replicated — POST /system/benchmarks/journaled-blp/run (simulatedRttMs=N)
#
# Why three tiers?
#   - Tier 1 reveals the real E2E ceiling (DB projector is the bottleneck at ~1K/s).
#   - Tier 2 reveals the BLP+journaling ceiling (bypasses DB and NATS output path).
#   - Tier 3 reveals the batch-ACK ceiling under simulated NATS RTT (HA branch model).
#
# Env knobs (all optional):
#   BASE_URL             — order-matcher base URL (default: http://localhost:18110)
#   NATS_URL             — NATS URL for avg-max-load.mjs (default: nats://localhost:4222)
#   E2E_RUNS             — cold-start runs for avg-max-load (default: 5)
#   E2E_SECS             — seconds per E2E run (default: 25)
#   WARMUP_ORDERS        — journaled-BLP warmup orders (default: 250000)
#   MEASURED_ORDERS      — journaled-BLP measured orders (default: 2000000)
#   RING_SIZE            — journaled-BLP ring size (default: 65536)
#   BATCH_RECORDS        — journal coalescing depth (default: 1024)
#   RTT_VALUES           — space-separated list of RTT values (ms) for tier 3 (default: "1 2 5 10")
#   SKIP_E2E             — set to 1 to skip tier 1 (useful when DB isn't running)
#   RESULTS_DIR          — output directory (default: scripts/bench/results/all-tiers-<timestamp>)
#
# Usage:
#   bash scripts/bench/run-all-tiers.sh
#   BASE_URL=https://yaakovseif.dev SKIP_E2E=1 bash scripts/bench/run-all-tiers.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:18110}"
NATS_URL="${NATS_URL:-nats://localhost:4222}"
E2E_RUNS="${E2E_RUNS:-5}"
E2E_SECS="${E2E_SECS:-25}"
WARMUP_ORDERS="${WARMUP_ORDERS:-250000}"
MEASURED_ORDERS="${MEASURED_ORDERS:-2000000}"
RING_SIZE="${RING_SIZE:-65536}"
BATCH_RECORDS="${BATCH_RECORDS:-1024}"
RTT_VALUES="${RTT_VALUES:-1 2 5 10}"
SKIP_E2E="${SKIP_E2E:-0}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="${RESULTS_DIR:-${SCRIPT_DIR}/results/all-tiers-${TIMESTAMP}}"

mkdir -p "${RESULTS_DIR}"
REPORT="${RESULTS_DIR}/REPORT.txt"

log() { echo "$@" | tee -a "${REPORT}"; }

log "============================================================"
log " lmax-kubernetes throughput tier comparison"
log " $(date)"
log " target   : ${BASE_URL}"
log "============================================================"
log ""

# ---- helpers ----------------------------------------------------------------

poll_benchmark() {
  local STATUS_URL="$1"
  local POLL_INTERVAL="${2:-5}"
  while true; do
    STATUS=$(curl -sf "${STATUS_URL}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('phase','?'))" 2>/dev/null || echo "unknown")
    if [[ "${STATUS}" == "complete" || "${STATUS}" == "failed" ]]; then
      curl -sf "${STATUS_URL}"
      echo ""
      return
    fi
    echo "  phase: ${STATUS} — polling in ${POLL_INTERVAL}s..." >&2
    sleep "${POLL_INTERVAL}"
  done
}

extract_field() {
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1', 0))"
}

# ---- Tier 1: E2E throughput -------------------------------------------------

if [[ "${SKIP_E2E}" != "1" ]]; then
  log "---- Tier 1: E2E throughput (avg-max-load.mjs, ${E2E_RUNS} cold-start runs) ----"
  E2E_OUT="${RESULTS_DIR}/tier1-e2e.txt"
  MATCHER_URL="${BASE_URL}" NATS_URL="${NATS_URL}" \
    node "${SCRIPT_DIR}/avg-max-load.mjs" --runs "${E2E_RUNS}" --secs "${E2E_SECS}" \
    --out "${E2E_OUT}" 2>&1 | tee -a "${REPORT}"
  log ""
else
  log "---- Tier 1: E2E throughput SKIPPED (SKIP_E2E=1) ----"
  log ""
fi

# ---- Tier 2: Journaled-BLP only (simulatedRttMs=0) --------------------------

log "---- Tier 2: BLP + journaling only (simulatedRttMs=0, batchRecords=${BATCH_RECORDS}) ----"
T2_RESULT_RAW=$(curl -sf -X POST \
  "${BASE_URL}/system/benchmarks/journaled-blp/run?warmupOrders=${WARMUP_ORDERS}&measuredOrders=${MEASURED_ORDERS}&ringSize=${RING_SIZE}&batchRecords=${BATCH_RECORDS}&simulatedRttMs=0" \
  -H "Accept: application/json")
log "  triggered: $(echo "${T2_RESULT_RAW}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('phase','?'))" 2>/dev/null)"

T2_RESULT=$(poll_benchmark "${BASE_URL}/system/benchmarks/journaled-blp")
T2_SUSTAINED=$(echo "${T2_RESULT}" | extract_field lastSustainedOrdersPerSecond)
T2_BLP_PEAK=$(echo "${T2_RESULT}" | extract_field blpPeak)
T2_TRADES_PEAK=$(echo "${T2_RESULT}" | extract_field tradesPeak)
echo "${T2_RESULT}" > "${RESULTS_DIR}/tier2-journaled-blp.json"
log "  sustained orders/sec : ${T2_SUSTAINED}"
log "  peak BLP/sec         : ${T2_BLP_PEAK}"
log "  peak trades/sec      : ${T2_TRADES_PEAK}"
log ""

# ---- Tier 3: Journaled-BLP + simulated replication RTT ----------------------

log "---- Tier 3: BLP + journaling + simulated replication RTT ----"
log "  (models lmax-kubernetes-blp-ha batch-ACK ceiling: batch_size / RTT)"
log ""

for RTT_MS in ${RTT_VALUES}; do
  log "  simulatedRttMs=${RTT_MS}ms ..."
  T3_RESULT_RAW=$(curl -sf -X POST \
    "${BASE_URL}/system/benchmarks/journaled-blp/run?warmupOrders=${WARMUP_ORDERS}&measuredOrders=${MEASURED_ORDERS}&ringSize=${RING_SIZE}&batchRecords=${BATCH_RECORDS}&simulatedRttMs=${RTT_MS}" \
    -H "Accept: application/json")
  T3_RESULT=$(poll_benchmark "${BASE_URL}/system/benchmarks/journaled-blp")
  T3_SUSTAINED=$(echo "${T3_RESULT}" | extract_field lastSustainedOrdersPerSecond)
  T3_BLP_PEAK=$(echo "${T3_RESULT}" | extract_field blpPeak)
  T3_TRADES_PEAK=$(echo "${T3_RESULT}" | extract_field tradesPeak)
  echo "${T3_RESULT}" > "${RESULTS_DIR}/tier3-rtt${RTT_MS}ms.json"
  log "    sustained orders/sec : ${T3_SUSTAINED}  |  peak BLP/sec : ${T3_BLP_PEAK}  |  peak trades/sec : ${T3_TRADES_PEAK}"
done

log ""

# ---- Summary ----------------------------------------------------------------

log "============================================================"
log " SUMMARY"
log "============================================================"
log ""
log "Tier 1 (E2E, DB projector gating)      : see ${RESULTS_DIR}/tier1-e2e.txt"
log "Tier 2 (BLP + journaling, no DB)       : ${T2_SUSTAINED} orders/sec sustained"
log "Tier 3 (BLP + journaling + replication): see per-RTT results in ${RESULTS_DIR}/"
log ""
log "Theoretical ceiling (no journaling, no replication): ~6M/sec (BlpBenchmarkService, improve-e2e branch)"
log "E2E ceiling driver: output-projector → Postgres (~1K persisted rows/sec; NOT the BLP)"
log ""
log "Results written to: ${RESULTS_DIR}/"
