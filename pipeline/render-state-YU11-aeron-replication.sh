#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU11-aeron-replication"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU10-fix-ingress"
STATE_DIR="${TARGET_ROOT}/YU11-aeron-replication"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU10-fix-ingress artifact missing for YU11 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU10-fix-ingress parity"
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
  system/adr-038-dual-transport-shadow-cutover.md \
  system/adr-039-sbe-epoch-wire-contract.md \
  system/adr-040-archive-catch-up-journal-authority.md \
  system/adr-041-durable-watermark-and-failure-policy.md \
  system/adr-042-archiving-media-driver-sidecar.md \
  system/adr-043-fast-failover-witness.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU11-aeron-replication Generated Artifacts

Dual-capable BLP replication on the YU10 order-matcher: File-backed NATS remains the default and
rollback path; Aeron reliable unicast plus generated SBE codecs and per-pod Archiving Media Driver
sidecars provide shadow validation, authoritative replication, Archive catch-up, exact follower-
journal durable ACKs, and an opt-in fast-witness failover path.

Parent lineage:

- parent state: `YU10-fix-ingress` (which renders onto `YU09-ops-hardening` ->
  `YU08-execution-algo-engine` -> `YU07-historical-tick-store` ->
  `YU06-eod-price-production` -> `YU05-post-trade-compliance` ->
  `YU04-durable-control-feeds` -> `YU03-in-memory-risk-gateway` ->
  `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

The selected transport is controlled by `BLP_REPLICATION_TRANSPORT=nats|aeron`; NATS remains the
default. See `spec-source/spec.md` and `spec-source/system__runtime-topology.md` for the complete
runtime and failure contracts.
EOF
