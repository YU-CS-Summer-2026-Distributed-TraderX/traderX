#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH_DIR="${ROOT}/scripts/bench"
RESULTS_DIR="${FANOUT_RESULTS_DIR:-${ROOT}/bench-results/fanout-$(date +%Y%m%d-%H%M%S)}"
MODE="${1:-}"

usage() {
  cat <<'EOF'
Usage:
  scripts/bench-output-fanout.sh run <label>
  scripts/bench-output-fanout.sh compare <a.json> <b.json>
  scripts/bench-output-fanout.sh state-009b-old-vs-direct

Modes:
  run
    Run one fan-out probe against the currently running runtime.
    Required env:
      FANOUT_STATE=009b
    Optional env:
      FANOUT_TIMEOUT_MS, FANOUT_ITERATIONS, FANOUT_ACCOUNT, FANOUT_SECURITY,
      MATCHER_URL, NATS_URL, FANOUT_QTY, FANOUT_QTY_STEP, FANOUT_LIMIT_PRICE,
      FANOUT_FILL_PRICE, FANOUT_FILL_PRICE_STEP

  compare
    Print the A/B report for two previously written JSON result files.

  state-009b-old-vs-direct
    Run the baseline lmax-sequencer-no-gc 009b old-fanout probe, then the current
    output-disruptor 009b direct-fanout probe, then compare.
    Start each runtime yourself before its corresponding step.
EOF
}

ensure_node() {
  command -v node >/dev/null 2>&1 || {
    echo "[error] node not found"
    exit 1
  }
}

ensure_bench_deps() {
  if [[ ! -d "${BENCH_DIR}/node_modules/nats" ]]; then
    echo "[setup] installing bench dependencies"
    (cd "${BENCH_DIR}" && npm install --no-audit --no-fund --silent)
  fi
}

run_probe() {
  local state="$1"
  local label="$2"
  local out="$3"

  echo "[run] state=${state} label=${label}"
  (
    cd "${BENCH_DIR}"
    FANOUT_STATE="${state}" \
    FANOUT_LABEL="${label}" \
    FANOUT_OUT="${out}" \
    node output-fanout-bench.mjs
  )

  if [[ ! -f "${out}" ]]; then
    echo "[error] probe did not write ${out}"
    exit 1
  fi
}

run_single_mode() {
  local label="${1:-}"
  [[ -n "${label}" ]] || {
    echo "[error] run mode needs a label"
    usage
    exit 1
  }
  [[ -n "${FANOUT_STATE:-}" ]] || {
    echo "[error] run mode requires FANOUT_STATE=009b"
    exit 1
  }
  mkdir -p "${RESULTS_DIR}"
  local out="${FANOUT_OUT:-${RESULTS_DIR}/${label}.json}"
  run_probe "${FANOUT_STATE}" "${label}" "${out}"
  echo "[done] wrote ${out}"
}

compare_mode() {
  local a="${1:-}"
  local b="${2:-}"
  [[ -n "${a}" && -n "${b}" ]] || {
    echo "[error] compare mode needs two json paths"
    usage
    exit 1
  }
  (cd "${BENCH_DIR}" && node output-fanout-bench.mjs --compare "${a}" "${b}")
}

scenario_state_009b_old_vs_direct() {
  mkdir -p "${RESULTS_DIR}"
  local a="${RESULTS_DIR}/fanout-009b-old.json"
  local b="${RESULTS_DIR}/fanout-009b-direct.json"

  echo "[step 1/3] Start 009b from branch lmax-sequencer-no-gc, then press Enter."
  read -r
  run_probe "009b" "009b-old-fanout" "${a}"

  echo "[step 2/3] Stop that runtime, start 009b from branch output-disruptor, then press Enter."
  read -r
  run_probe "009b" "009b-direct-fanout" "${b}"

  echo "[step 3/3] Compare"
  compare_mode "${a}" "${b}"
  echo "[done] results in ${RESULTS_DIR}"
}

case "${MODE}" in
  run)
    ensure_node
    ensure_bench_deps
    run_single_mode "${2:-}"
    ;;
  compare)
    ensure_node
    compare_mode "${2:-}" "${3:-}"
    ;;
  state-009b-old-vs-direct)
    ensure_node
    ensure_bench_deps
    scenario_state_009b_old_vs_direct
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "[error] unknown mode: ${MODE}"
    usage
    exit 1
    ;;
esac
