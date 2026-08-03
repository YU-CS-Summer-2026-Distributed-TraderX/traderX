#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
STATE_DIR="${GENERATED_ROOT}/code/target-generated/YU14-listed-equity-options"
UPSTREAM_STATUS_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/status-state-YU10-fix-ingress-generated.sh"

echo "[status] state=YU14-listed-equity-options"
if [[ -d "${STATE_DIR}" ]]; then
  echo "[status] scaffold=generated"
  echo "[status] runtime=inherits-YU11-aeron-replication-plus-aeron-cluster"
  if [[ -x "${UPSTREAM_STATUS_SCRIPT}" ]]; then
    "${UPSTREAM_STATUS_SCRIPT}" "$@"
  fi
else
  echo "[status] scaffold=missing"
  echo "[hint] run: bash pipeline/generate-state.sh YU14-listed-equity-options"
fi
