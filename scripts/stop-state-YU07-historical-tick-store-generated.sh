#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi

if [[ "${TRADERX_LOCAL_RUNTIME_SCRIPT:-0}" != "1" ]]; then
  LOCAL_RUNTIME_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/$(basename "${BASH_SOURCE[0]}")"
  if [[ -x "${LOCAL_RUNTIME_SCRIPT}" ]]; then
    exec "${LOCAL_RUNTIME_SCRIPT}" "$@"
  fi
fi

UPSTREAM_STOP_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/stop-state-014-fdc3-intent-interoperability-generated.sh"

[[ -x "${UPSTREAM_STOP_SCRIPT}" ]] || {
  echo "[error] missing upstream stop script: ${UPSTREAM_STOP_SCRIPT}"
  echo "[hint] run: bash pipeline/generate-state.sh YU07-historical-tick-store"
  exit 1
}

exec "${UPSTREAM_STOP_SCRIPT}" "$@"
