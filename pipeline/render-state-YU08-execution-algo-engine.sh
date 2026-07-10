#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU08-execution-algo-engine"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU07-historical-tick-store"
STATE_DIR="${TARGET_ROOT}/YU08-execution-algo-engine"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU07-historical-tick-store artifact missing for YU08 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU07-historical-tick-store parity"
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
  quickstart.md \
  contracts/contract-delta.md \
  requirements/functional-delta.md \
  requirements/nonfunctional-delta.md \
  system/architecture.md \
  system/architecture.model.json \
  system/runtime-topology.md \
  system/messaging-subject-map.md \
  system/adr-030-warm-path-algo-engine-with-jetstream-event-sourcing.md \
  system/adr-031-pluggable-volume-profile-source.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU08-execution-algo-engine Generated Artifacts

Execution algo engine: a new `execution-algo-engine` component slicing a parent order into TWAP
(equal time-bucketed) or VWAP (volume-weighted) child orders, submitted through order-matcher's
existing order-entry and risk-gateway path, onto the `YU07-historical-tick-store` runtime.

Parent lineage:

- parent state: `YU07-historical-tick-store` (which renders onto `YU06-eod-price-production` ->
  `YU05-post-trade-compliance` -> `YU04-durable-control-feeds` -> `YU03-in-memory-risk-gateway` ->
  `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

Changes are the new `execution-algo-engine` component (scheduling, event-sourced state, fill
tracking, pluggable VWAP volume profile) plus its Deployment/Service manifests; the deploy/runtime
harness and every existing service (including YU07's `tick-store`) are inherited unchanged from
`YU07-historical-tick-store` (see `spec-source/spec.md` for scope).
EOF
