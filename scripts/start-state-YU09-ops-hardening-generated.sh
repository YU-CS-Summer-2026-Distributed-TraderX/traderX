#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
STATE_ID="YU09-ops-hardening"
STATE_DIR="${GENERATED_ROOT}/code/target-generated/${STATE_ID}"
UPSTREAM_START_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/start-state-YU08-execution-algo-engine-generated.sh"

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

echo "[info] ${STATE_ID} reuses the YU08-execution-algo-engine (=> YU07 => YU06 => YU05 => YU04 => YU03 =>"
echo "[info] YU02 => 014) runtime harness; DB/JWT credentials now come from mariadb-credentials/"
echo "[info] auth-secrets Secrets (create them once per cluster — see quickstart.md) and the"
echo "[info] order-matcher journal rotates+archives to GCS when journal.archive.enabled=true."
echo "[info] state metadata: ${STATE_DIR}"
exec "${UPSTREAM_START_SCRIPT}" "$@"
