#!/usr/bin/env bash
set -euo pipefail

# A/B benchmark: the identical order+tick workload through a running state 009
# (poll/lock/inline-JPA matcher) and then a running state 009b (LMAX hot path), with a
# full clean + teardown (containers AND volumes AND the generated tree) before and after
# each run. Prints the per-phase timing difference at the end.
#
# Workload per state (see scripts/bench/load/order-matcher-bench.mjs):
#   BENCH_ORDERS resting orders via REST, then BENCH_TICKS NATS price ticks on a
#   descending price ramp that progressively crosses every order, then drain to all-filled.
#
# Usage:
#   scripts/bench-009-vs-009b.sh
# Knobs (env): BENCH_ORDERS=5000 BENCH_TICKS=3000000 BENCH_TICK_RATE=25000
#   BENCH_RESULTS_DIR=bench-results/<timestamp> BENCH_SKIP_GENERATE=0
#
# Requirements: docker compose, Linux node (nvm ok), network for image builds.
# Note: the full stock stack runs in both states, including price-publisher (trade-service
# needs it to quote bookings). The driver guarantees a deterministic ramp anyway — see the
# tickers/limits note in order-matcher-bench.mjs.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
COMPOSE_PROJECT="traderx-state-009"
RESULTS_DIR="${BENCH_RESULTS_DIR:-${ROOT}/bench-results/$(date +%Y%m%d-%H%M%S)}"
BENCH_DIR="${ROOT}/scripts/bench"
STATES=(009-order-management-matcher 009b-lmax-sequencer-architecture)

# --- toolchain -------------------------------------------------------------------------

if ! command -v node >/dev/null 2>&1; then
  # shellcheck disable=SC1091
  [[ -s "${HOME}/.nvm/nvm.sh" ]] && source "${HOME}/.nvm/nvm.sh"
fi
command -v node >/dev/null 2>&1 || { echo "[error] node not found (pipeline + driver need it)"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "[error] docker not found"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[error] docker compose plugin required"; exit 1; }

if [[ ! -d "${BENCH_DIR}/node_modules/nats" ]]; then
  echo "[setup] installing bench driver dependencies"
  (cd "${BENCH_DIR}" && npm install --no-audit --no-fund --silent)
fi

mkdir -p "${RESULTS_DIR}"
echo "[bench] results -> ${RESULTS_DIR}"

# --- stack lifecycle helpers -------------------------------------------------------------

compose_file() {
  # Parent states leave their own compose files in the tree; the state runtime is the
  # one that defines the order-matcher service (newest if several).
  grep -l '^  order-matcher:' "${TARGET_ROOT}"/*/docker-compose.yml 2>/dev/null \
    | xargs -r ls -1t 2>/dev/null | head -1 || true
}

full_teardown() {
  local label="$1"
  local cf
  cf="$(compose_file)"
  if [[ -n "${cf}" ]]; then
    docker compose -f "${cf}" --project-name "${COMPOSE_PROJECT}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  # Catch containers/volumes from a previous tree even if the compose file is gone.
  docker compose --project-name "${COMPOSE_PROJECT}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  echo "[clean] ${label}: compose project ${COMPOSE_PROJECT} down (volumes removed)"
}

wait_quiet() {
  # Give the bus/services a settling moment after startup before measuring.
  sleep 5
}

run_state() {
  local state="$1"
  local result_json="${RESULTS_DIR}/${state}.json"
  local log="${RESULTS_DIR}/${state}.stack.log"

  echo ""
  echo "=== [${state}] pre-run clean ==="
  full_teardown "pre-${state}"

  if [[ "${BENCH_SKIP_GENERATE:-0}" != "1" ]]; then
    echo "=== [${state}] generate ==="
    rm -rf "${TARGET_ROOT}"
    bash "${ROOT}/pipeline/generate-state.sh" "${state}" >>"${log}" 2>&1 \
      || { echo "[error] generation failed; see ${log}"; exit 1; }
  fi

  echo "=== [${state}] start stack (build + health waits; logging to ${log}) ==="
  local start_script="${TARGET_ROOT}/scripts/start-state-${state}-generated.sh"
  [[ -x "${start_script}" ]] || start_script="${ROOT}/scripts/start-state-${state}-generated.sh"
  TRADERX_SKIP_GENERATE=1 bash "${start_script}" >>"${log}" 2>&1 \
    || { echo "[error] stack start failed; see ${log}"; exit 1; }

  # price-publisher MUST stay running: trade-service fetches an execution price from it
  # on every booking (fills would all be rejected without it). Determinism comes from the
  # driver instead: bench tickers are outside the publisher's default set and every bench
  # limit sits below the publisher's minimum possible fallback price, so only the
  # driver's ramp can cross a limit (see order-matcher-bench.mjs).
  wait_quiet

  echo "=== [${state}] benchmark ==="
  local bench_rc=0
  node "${BENCH_DIR}/order-matcher-bench.mjs" --state "${state}" --out "${result_json}" \
    | tee "${RESULTS_DIR}/${state}.bench.log" || bench_rc=$?

  echo "=== [${state}] post-run teardown ==="
  if (( bench_rc != 0 )); then
    # Forensics before the stack disappears: why did bookings/fills misbehave?
    local cf
    cf="$(compose_file)"
    if [[ -n "${cf}" ]]; then
      docker compose -f "${cf}" --project-name "${COMPOSE_PROJECT}" logs --tail 500 \
        trade-service order-matcher > "${RESULTS_DIR}/${state}.failure-logs.txt" 2>&1 || true
      echo "[forensics] saved trade-service/order-matcher logs to ${RESULTS_DIR}/${state}.failure-logs.txt"
    fi
  fi
  bash "${TARGET_ROOT}/scripts/stop-state-${state}-generated.sh" >>"${log}" 2>&1 || true
  full_teardown "post-${state}"

  [[ -f "${result_json}" ]] || { echo "[error] no result written for ${state} (rc=${bench_rc})"; exit 1; }
  if (( bench_rc != 0 )); then
    echo "[warn] ${state} bench exited rc=${bench_rc} (e.g. drain timeout); continuing with recorded result"
  fi
}

for state in "${STATES[@]}"; do
  run_state "${state}"
done

echo ""
node "${BENCH_DIR}/order-matcher-bench.mjs" --compare \
  "${RESULTS_DIR}/${STATES[0]}.json" "${RESULTS_DIR}/${STATES[1]}.json" \
  | tee "${RESULTS_DIR}/REPORT.txt"
echo ""
echo "[done] full results in ${RESULTS_DIR}"
