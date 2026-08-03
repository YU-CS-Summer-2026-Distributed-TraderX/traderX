#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU06-eod-price-production"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU05-post-trade-compliance"
STATE_DIR="${TARGET_ROOT}/YU06-eod-price-production"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU05-post-trade-compliance artifact missing for YU06 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU05-post-trade-compliance parity"
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
  system/adr-026-last-trade-close-versioned-immutable-snapshot.md \
  system/adr-027-jetstream-event-chain-orchestration.md \
  system/adr-028-producer-consumer-split-failsafe.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU06-eod-price-production Generated Artifacts

EOD price production + overnight batch chain, gated by a durable `EOD_PRICES_READY` JetStream event,
onto the `YU05-post-trade-compliance` runtime.

Parent lineage:

- parent state: `YU05-post-trade-compliance` (which renders onto `YU04-durable-control-feeds` ->
  `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

Changes are trade-processor (EOD price producer) + position-service (EOD P&L consumer) runtime
overrides, plus a database-init ConfigMap (three EOD tables), an `eod-session-close` CronJob, and a
Grafana chain-status dashboard; the deploy/runtime harness is inherited unchanged from
`YU05-post-trade-compliance` (see `spec-source/spec.md` for scope and deferrals).
EOF
