#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU17-otc-rates"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU16-cdm-instruments"
STATE_DIR="${TARGET_ROOT}/YU17-otc-rates"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU16-cdm-instruments artifact missing for YU17 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU16-cdm-instruments parity"
  fi
}

overlay_dir "${RUNTIME_OVERRIDES_DIR}" "${TARGET_ROOT}" "runtime"

# YU17 carries no reference-data of its own. instruments.csv is inherited from YU16 and
# counterparties.csv from YU15, both operative; copying either here would shadow them for no
# delta. The contracts artifact reads counterparties.csv exactly as the netted extract does.

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
  system/adr-062-swap-booking-through-consensus.md \
  system/adr-063-swap-risk-gate.md \
  system/adr-064-two-artifacts-one-cut.md \
  system/adr-065-swaptions-as-contract-records.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU17-otc-rates Generated Artifacts

OTC interest-rate swaps on the cluster tier: the first instrument class that neither matches nor
nets. A swap booking is a sequenced consensus command (`TYPE_SWAP_BOOK` on the existing SBE
template 1) that creates a contract in replicated state and is never handed to the matching
engine — no order, no book, no crossing, no position. The risk gate gets a swap path, because
`quantity x price x multiplier` values a 10mm swap at 420,000. One EOD cut at one consensus
sequence renders two artifacts: the netted position extract unchanged at CSV schema 3, and a
per-contract swap artifact carrying terms and no valuation. Snapshot format 4 -> 5 for the new
contract record, with `MIN_READABLE_SNAPSHOT_FORMAT` held at 3 so an existing epoch rolls
forward without a wipe.

Parent lineage:

- parent state: `YU16-cdm-instruments` (which renders onto `YU15-eod-risk-extract` ->
  `YU14-listed-equity-options` -> `YU13-limit-order-book` -> `YU12-aeron-cluster` ->
  `YU11-aeron-replication` -> `YU10-fix-ingress` -> `YU09-ops-hardening` ->
  `YU08-execution-algo-engine` -> `YU07-historical-tick-store` ->
  `YU06-eod-price-production` -> `YU05-post-trade-compliance` ->
  `YU04-durable-control-feeds` -> `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` ->
  `014-fdc3-intent-interoperability`)

See `spec-source/spec.md` and `spec-source/system__runtime-topology.md` for the complete
runtime and failure contracts.
EOF
