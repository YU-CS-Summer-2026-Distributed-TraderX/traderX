#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU05-post-trade-compliance"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU04-durable-control-feeds"
STATE_DIR="${TARGET_ROOT}/YU05-post-trade-compliance"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU04-durable-control-feeds artifact missing for YU05 render: ${PARENT_STATE_DIR}"
  exit 1
}

# Overlay the state's runtime overrides onto the shared component tree, exactly like every prior
# state in this lineage does with its own overrides (parent overlays already applied).
overlay_dir() {
  local src="$1"
  local dst="$2"
  local label="$3"
  if [[ -d "${src}" ]] && find "${src}" -type f -print -quit | grep -q .; then
    mkdir -p "${dst}"
    tar -C "${src}" \
      --exclude='./*/.parent-src' --exclude='./.parent-src' \
      --exclude='./*/.gradle' --exclude='./.gradle' \
      --exclude='./*/gradlew' --exclude='./gradlew' \
      --exclude='./*/gradlew.bat' --exclude='./gradlew.bat' \
      --exclude='./*/gradle/wrapper' --exclude='./gradle/wrapper' \
      -cf - . \
      | tar -C "${dst}" -xf -
    echo "[render] overlaid ${label} from ${src}"
  else
    echo "[info] no ${label} overrides present (${src}); keeping YU04-durable-control-feeds parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"

rm -rf "${STATE_DIR}"
mkdir -p "${STATE_DIR}" "${SPEC_SOURCE_DIR}"

for source in \
  README.md \
  spec.md \
  plan.md \
  research.md \
  data-model.md \
  contracts/contract-delta.md \
  requirements/functional-delta.md \
  requirements/nonfunctional-delta.md \
  system/architecture.md \
  system/runtime-topology.md \
  system/adr-022-deterministic-trade-identity-and-settlement-recon.md \
  system/adr-023-journal-sourced-regulatory-reporting.md \
  system/adr-024-pluggable-tca-benchmark-source.md \
  system/adr-025-oidc-entitlements-gate-post-trade-apis.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU05-post-trade-compliance Generated Artifacts

Bundled post-trade compliance state: deterministic trade identity + settlement + reconciliation
(slice 1, implemented), regulatory reporting / TCA / real auth (specified, deferred), onto the
`YU04-durable-control-feeds` runtime.

Parent lineage:

- parent state: `YU04-durable-control-feeds` (which renders onto `YU03-in-memory-risk-gateway` ->
  `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

All slice-1 changes are order-matcher + trade-processor runtime overrides plus a database-init
ConfigMap column addition; the deploy/runtime harness is inherited unchanged from
`YU04-durable-control-feeds` (see `spec-source/spec.md` for scope and deferrals).
EOF
