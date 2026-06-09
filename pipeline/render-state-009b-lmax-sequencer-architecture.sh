#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
STATE_ID="009b-lmax-sequencer-architecture"
RUNTIME_OVERRIDES_DIR="${ROOT}/specs/${STATE_ID}/generation/runtime-overrides"
FRONTEND_OVERRIDE_SOURCE_DIR="${ROOT}/specs/${STATE_ID}/generation/frontend-overrides/web-front-end/angular"
TARGET_FRONTEND_DIR="${TARGET_ROOT}/web-front-end/angular"

# Render scaffold for the LMAX hot-path state. The parent (009) render has
# already materialized the full runtime; this script overlays 009b-specific
# assets as they are implemented (tasks T09B11..T09B20):
#   - runtime-overrides/: rebuilt order-matcher hot path (rings, BLP, SBE,
#     journal, projector), gateway changes, observability assets
#   - frontend-overrides/: none expected beyond inherited 009 overrides
#     (state identity is installed by install-generated-ui-state-metadata.sh)

overlay_dir() {
  local src="$1"
  local dst="$2"
  local label="$3"
  if [[ -d "${src}" ]] && find "${src}" -type f -print -quit | grep -q .; then
    mkdir -p "${dst}"
    cp -R "${src}/." "${dst}/"
    echo "[render] overlaid ${label} from ${src}"
  else
    echo "[info] no ${label} overrides present yet (${src}); keeping 009 parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"
overlay_dir "${FRONTEND_OVERRIDE_SOURCE_DIR}" "${TARGET_FRONTEND_DIR}" "frontend"

echo "[done] render pass complete for ${STATE_ID}"
