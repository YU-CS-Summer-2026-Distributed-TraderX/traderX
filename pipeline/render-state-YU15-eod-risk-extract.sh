#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU15-eod-risk-extract"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU14-listed-equity-options"
STATE_DIR="${TARGET_ROOT}/YU15-eod-risk-extract"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU14-listed-equity-options artifact missing for YU15 render: ${PARENT_STATE_DIR}"
  exit 1
}

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
    echo "[info] no ${label} overrides present (${src}); keeping YU14-listed-equity-options parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"

# The extract producer reads counterparties.csv at runtime, so the spec pack's reference data is
# rendered into order-matcher resources (which the image copies wholesale to /opt/app/classes).
# The spec pack stays the single source of truth - nothing is hand-copied into the override tree.
if [[ -d "${STATE_SPEC_DIR}/reference-data" ]]; then
  REFDATA_DIR="${TARGET_ROOT}/order-matcher/src/main/resources/reference-data"
  mkdir -p "${REFDATA_DIR}"
  cp "${STATE_SPEC_DIR}/reference-data/"*.csv "${REFDATA_DIR}/"
  echo "[render] overlaid reference-data from ${STATE_SPEC_DIR}/reference-data"
fi

rm -rf "${STATE_DIR}"
mkdir -p "${STATE_DIR}" "${SPEC_SOURCE_DIR}"

if [[ -d "${STATE_SPEC_DIR}/generation/compose" ]]; then
  mkdir -p "${STATE_DIR}/runtime"
  cp "${STATE_SPEC_DIR}/generation/compose/"*.yml "${STATE_DIR}/runtime/"
fi
if [[ -d "${STATE_SPEC_DIR}/generation/kubernetes" ]]; then
  mkdir -p "${STATE_DIR}/runtime"
  cp -R "${STATE_SPEC_DIR}/generation/kubernetes" "${STATE_DIR}/runtime/kubernetes"
fi

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
  system/snapshot-completeness-matrix.md \
  system/adr-055-sequenced-extract-marker.md \
  system/adr-056-eod-mark-sourcing.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU15-eod-risk-extract Generated Artifacts

The end-of-day risk extract: on `eod.pnl.done`, a sequenced marker names a consensus
sequence N, every cluster member renders the identical position cut at N, and the leader
publishes it. The producer joins that cut with the YU06 published closes and the
counterparty reference data into one immutable, byte-reproducible CSV fixture, written
write-once and announced on `risk.extract.ready`.

Parent lineage:

- parent state: `YU14-listed-equity-options` (which renders onto `YU13-limit-order-book` ->
  `YU12-aeron-cluster` -> `YU11-aeron-replication` ->
  `YU10-fix-ingress` -> `YU09-ops-hardening` -> `YU08-execution-algo-engine` ->
  `YU07-historical-tick-store` -> `YU06-eod-price-production` ->
  `YU05-post-trade-compliance` -> `YU04-durable-control-feeds` ->
  `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` ->
  `014-fdc3-intent-interoperability`)

See `spec-source/spec.md` and `spec-source/system__runtime-topology.md` for the complete
runtime and failure contracts.
EOF
