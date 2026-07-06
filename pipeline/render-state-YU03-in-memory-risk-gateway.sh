#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU03-in-memory-risk-gateway"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU02-lmax-kubernetes"
STATE_DIR="${TARGET_ROOT}/YU03-in-memory-risk-gateway"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU02-lmax-kubernetes artifact missing for YU03 render: ${PARENT_STATE_DIR}"
  exit 1
}

# Overlay the state's runtime overrides onto the shared component tree, exactly like the
# YU02-lmax-kubernetes render does with its own overrides (parent overlays already applied).
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
    echo "[info] no ${label} overrides present (${src}); keeping YU02-lmax-kubernetes parity"
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
  requirements/no-gc-conformance.md \
  system/architecture.md \
  system/runtime-topology.md \
  system/adr-018-two-stage-validation.md \
  system/adr-019-watermarked-replica-bootstrap.md \
  system/adr-020-control-events-in-global-journal.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU03-in-memory-risk-gateway Generated Artifacts

Forward-port of the in-memory risk gateway design (two-tier: Gateway replica screening +
authoritative deterministic BLP risk decision, SEC 15c3-5 control baseline) onto the
`YU02-lmax-kubernetes` runtime.

Parent lineage:

- parent state: `YU02-lmax-kubernetes` (which renders onto `014-fdc3-intent-interoperability`)
- design mined from the pre-k8s `in-memory-risk-gateway` branch's spec pack

All slice-1 changes are order-matcher runtime overrides; the deploy/runtime harness is
inherited unchanged from `YU02-lmax-kubernetes` (see `spec-source/spec.md` for scope and deferrals).
EOF
