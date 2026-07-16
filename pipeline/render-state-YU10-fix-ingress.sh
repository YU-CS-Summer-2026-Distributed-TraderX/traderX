#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU10-fix-ingress"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU09-ops-hardening"
STATE_DIR="${TARGET_ROOT}/YU10-fix-ingress"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU09-ops-hardening artifact missing for YU10 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU09-ops-hardening parity"
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
  system/adr-034-quickfixj-in-process-acceptor.md \
  system/adr-035-durable-correlation-ledger.md \
  system/adr-036-fail-closed-session-identity.md \
  system/adr-037-ambiguous-outcome-model.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU10-fix-ingress Generated Artifacts

FIX 4.4 order-entry ingress: an in-process QuickFIX/J acceptor in the order-matcher whose
sessions feed the existing LMAX input ring (same risk screen, journal, and recovery as REST),
with a durable ClOrdID correlation ledger on the PVC and asynchronous ExecutionReports delivered
from a dedicated output-disruptor handler. Session identity is fail-closed: JWT at logon plus a
committed CompID->account allowlist.

Parent lineage:

- parent state: `YU09-ops-hardening` (which renders onto `YU08-execution-algo-engine` ->
  `YU07-historical-tick-store` -> `YU06-eod-price-production` -> `YU05-post-trade-compliance` ->
  `YU04-durable-control-feeds` -> `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` ->
  `014-fdc3-intent-interoperability`)

No new service. Changes are a `fix/` package plus QuickFIX/J dependency in `order-matcher`, one
output-handler registration in `LmaxEngine`, and the acceptor port + FIX data directory on the
existing order-matcher manifests; everything else is inherited unchanged from
`YU09-ops-hardening` (see `spec-source/spec.md` for scope).
EOF
