#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/lmax-kubernetes"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
UPSTREAM_DIR="${TARGET_ROOT}/fdc3-intent-interoperability"
STATE_DIR="${TARGET_ROOT}/lmax-kubernetes"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${UPSTREAM_DIR}" ]] || {
  echo "[fail] required state 014 artifact missing for lmax-kubernetes render: ${UPSTREAM_DIR}"
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
    echo "[info] no ${label} overrides present yet (${src}); keeping 014 parity"
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
  tasks.md \
  contracts/contract-delta.md \
  requirements/functional-delta.md \
  requirements/nonfunctional-delta.md \
  generation/generation-hook.md \
  generation/implementation-status.md \
  generation/port-matrix.md \
  system/architecture.md \
  system/runtime-topology.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# lmax-kubernetes Generated Artifacts

This generated directory tracks the in-progress `lmax-kubernetes` forward port.

Parent lineage:

- inherited runtime reference: `generated/code/target-generated/fdc3-intent-interoperability`
- target state id: `lmax-kubernetes`

Current status:

- state registration and spec-pack scaffold are complete
- runtime overrides are beginning to land directly onto the `014` generated runtime
- the port is not yet complete enough to claim runtime parity with either `009b` or `014`

Useful files:

- `spec-source/spec.md`
- `spec-source/plan.md`
- `spec-source/generation__port-matrix.md`
- `spec-source/generation__implementation-status.md`

Runtime note:

- this state currently reuses the `014` runtime harness while `009b` LMAX overlays are layered in incrementally
EOF

cat > "${STATE_DIR}/IMPLEMENTATION-STATUS.md" <<'EOF'
# lmax-kubernetes Implementation Status

Phase: overlay-in-progress

The generated state exists to anchor forward-port work from:

- `009b-lmax-sequencer-architecture`
- onto `014-fdc3-intent-interoperability`

Runtime overlays are now applied onto `generated/code/target-generated`, but the state is not yet
validated as runnable `009b` parity on the `014` Kubernetes baseline.
EOF
