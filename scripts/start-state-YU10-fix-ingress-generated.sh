#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
STATE_ID="YU10-fix-ingress"
STATE_DIR="${GENERATED_ROOT}/code/target-generated/${STATE_ID}"
UPSTREAM_START_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/start-state-YU09-ops-hardening-generated.sh"

if [[ "${TRADERX_LOCAL_RUNTIME_SCRIPT:-0}" != "1" ]]; then
  LOCAL_RUNTIME_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/$(basename "${BASH_SOURCE[0]}")"
  if [[ -x "${LOCAL_RUNTIME_SCRIPT}" ]]; then
    exec "${LOCAL_RUNTIME_SCRIPT}" "$@"
  fi
fi

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  bash "${REPO_ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
fi

[[ -x "${UPSTREAM_START_SCRIPT}" ]] || {
  echo "[fail] missing upstream runtime harness: ${UPSTREAM_START_SCRIPT}"
  exit 1
}

echo "[info] ${STATE_ID} reuses the YU09-ops-hardening (=> YU08 => YU07 => YU06 => YU05 => YU04"
echo "[info] => YU03 => YU02 => 014) runtime harness; adds the in-process FIX 4.4 acceptor on"
echo "[info] port 18130 (fail-closed logon: mapped CompID + JWT in Password(554)) with its store"
echo "[info] and correlation ledger on the order-matcher PVC."
echo "[info] state metadata: ${STATE_DIR}"
exec "${UPSTREAM_START_SCRIPT}" "$@"
