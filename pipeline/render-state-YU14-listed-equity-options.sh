#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU14-listed-equity-options"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU13-limit-order-book"
STATE_DIR="${TARGET_ROOT}/YU14-listed-equity-options"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU13-limit-order-book artifact missing for YU14 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU13-limit-order-book parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"

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
  system/adr-049-crossing-limit-order-book.md \
  system/adr-050-banded-price-level-arrays.md \
  system/adr-051-last-trade-price-output.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU14-listed-equity-options Generated Artifacts

Listed equity options trade on the crossing limit-order book as ordinary securities: an
option contract is an OCC-symbol security identifier with a two-sided book. The state adds
the instrument reference-data model (underlying, strike, expiry, call/put derived from the
OCC identifier; currency and counterparty as extract-time reference data) and makes the
risk gate's notional math contract-multiplier-aware (qty x price x multiplier), with the
multiplier carried in cluster state and the snapshot (format 3, fail-closed restore).

Parent lineage:

- parent state: `YU13-limit-order-book` (which renders onto `YU12-aeron-cluster` ->
  `YU11-aeron-replication` ->
  `YU10-fix-ingress` -> `YU09-ops-hardening` -> `YU08-execution-algo-engine` ->
  `YU07-historical-tick-store` -> `YU06-eod-price-production` ->
  `YU05-post-trade-compliance` -> `YU04-durable-control-feeds` ->
  `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` ->
  `014-fdc3-intent-interoperability`)

See `spec-source/spec.md` and `spec-source/system__runtime-topology.md` for the complete
runtime and failure contracts.
EOF
