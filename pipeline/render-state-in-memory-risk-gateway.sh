#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
STATE_ID="in-memory-risk-gateway"
RUNTIME_OVERRIDES_DIR="${ROOT}/specs/${STATE_ID}/generation/runtime-overrides"
FRONTEND_OVERRIDES_DIR="${ROOT}/specs/${STATE_ID}/generation/frontend-overrides/web-front-end/angular"

overlay_dir() {
  local src="$1"
  local dst="$2"
  local label="$3"
  if [[ -d "${src}" ]] && find "${src}" -type f -print -quit | grep -q .; then
    mkdir -p "${dst}"
    tar -C "${src}" \
      --exclude='./*/.parent-src' --exclude='./.parent-src' \
      --exclude='./*/gradlew' --exclude='./*/gradlew.bat' --exclude='./*/gradle/wrapper' \
      -cf - . \
      | tar -C "${dst}" -xf -
    echo "[render] overlaid ${label} from ${src}"
  else
    echo "[info] no ${label} overrides present (${src}); keeping ${STATE_ID} parent parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"
overlay_dir "${FRONTEND_OVERRIDES_DIR}" "${TARGET_ROOT}/web-front-end/angular" "frontend"

echo "[done] render pass complete for ${STATE_ID}"
