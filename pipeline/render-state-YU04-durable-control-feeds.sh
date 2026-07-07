#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU04-durable-control-feeds"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU03-in-memory-risk-gateway"
STATE_DIR="${TARGET_ROOT}/YU04-durable-control-feeds"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU03-in-memory-risk-gateway artifact missing for YU04 render: ${PARENT_STATE_DIR}"
  exit 1
}

# Overlay the state's runtime overrides onto the shared component tree, exactly like the
# YU03-in-memory-risk-gateway render does with its own overrides (parent overlays already applied).
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
      --exclude='./*/node_modules' --exclude='./node_modules' \
      -cf - . \
      | tar -C "${dst}" -xf -
    echo "[render] overlaid ${label} from ${src}"
  else
    echo "[info] no ${label} overrides present (${src}); keeping YU03-in-memory-risk-gateway parity"
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
  system/adr-021-transactional-outbox-jetstream-feeds.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU04-durable-control-feeds Generated Artifacts

Adopts ADR-019's watermarked-snapshot-plus-buffered-deltas replica bootstrap protocol: real
durable outbox feeds from account-service and reference-data into NATS JetStream, replacing
YU03's one-shot REST bootstrap in order-matcher's ReplicaBootstrap.

Parent lineage:

- parent state: `YU03-in-memory-risk-gateway` (which renders onto `YU02-lmax-kubernetes` => `014-fdc3-intent-interoperability`)
- design baseline: ADR-019 (written in full during YU03, deferred there), ADR-021 (this state's
  own outbox mechanism decision)

This state's changes span order-matcher, account-service, and reference-data runtime overrides;
the deploy/runtime harness is inherited unchanged from `YU03-in-memory-risk-gateway` (see
`spec-source/spec.md` for scope and `spec-source/data-model.md` for the new schema).
EOF
