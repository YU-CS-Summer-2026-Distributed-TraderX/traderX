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
    # .parent-src is an IDE-only snapshot of parent-state sources for in-place
    # overlay development; generated trees already contain those files in src/.
    tar -C "${src}" --exclude='./*/.parent-src' --exclude='./.parent-src' -cf - . \
      | tar -C "${dst}" -xf -
    echo "[render] overlaid ${label} from ${src}"
  else
    echo "[info] no ${label} overrides present yet (${src}); keeping 009 parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"
overlay_dir "${FRONTEND_OVERRIDE_SOURCE_DIR}" "${TARGET_FRONTEND_DIR}" "frontend"

# Observability ingress routes (/grafana, /prometheus) are injected by the 009
# render only at generation depth 1; when 009 runs nested as this state's
# parent, the injection is skipped. Re-apply it here (same idempotent mutation
# as render-state-009-order-management-matcher.sh) so 009b keeps 009's ingress
# observability parity.
ensure_observability_ingress_routes() {
  local ingress_file="$1"
  local tmp_file
  [[ -f "${ingress_file}" ]] || return 0
  if rg -q "location /grafana/" "${ingress_file}" && rg -q "location /prometheus/" "${ingress_file}"; then
    return 0
  fi

  tmp_file="$(mktemp)"
  awk '
    !added && $0 ~ /^[[:space:]]*location \/ \{/ {
      print "    location = /grafana {"
      print "        return 301 /grafana/;"
      print "    }"
      print ""
      print "    location /grafana/ {"
      print "        proxy_pass http://grafana:3000;"
      print "        proxy_http_version 1.1;"
      print "        proxy_set_header Host $http_host;"
      print "        proxy_set_header X-Forwarded-Proto $scheme;"
      print "        proxy_set_header X-Forwarded-Prefix /grafana;"
      print "    }"
      print ""
      print "    location = /prometheus {"
      print "        return 301 /prometheus/;"
      print "    }"
      print ""
      print "    location /prometheus/ {"
      print "        proxy_pass http://prometheus:9090;"
      print "        proxy_http_version 1.1;"
      print "        proxy_set_header Host $http_host;"
      print "        proxy_set_header X-Forwarded-Proto $scheme;"
      print "        proxy_set_header X-Forwarded-Prefix /prometheus;"
      print "    }"
      print ""
      added = 1
    }
    { print }
  ' "${ingress_file}" > "${tmp_file}"
  mv "${tmp_file}" "${ingress_file}"
}

GEN_DEPTH="${TRADERX_GENERATION_DEPTH:-1}"
if [[ "${GEN_DEPTH}" == "1" ]]; then
  ensure_observability_ingress_routes "${TARGET_ROOT}/ingress/nginx.traderx.conf.template"
else
  echo "[info] nested generation depth=${GEN_DEPTH}; skipping ingress observability route mutation"
fi

echo "[done] render pass complete for ${STATE_ID}"
