#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU12-aeron-cluster"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU11-aeron-replication"
STATE_DIR="${TARGET_ROOT}/YU12-aeron-cluster"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU11-aeron-replication artifact missing for YU12 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU11-aeron-replication parity"
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
  system/adr-044-aeron-cluster-consensus.md \
  system/adr-045-consensus-log-single-input.md \
  system/adr-046-cluster-snapshot-completeness.md \
  system/adr-047-ingress-gateway-tier.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU12-aeron-cluster Generated Artifacts

BLP high availability as Raft consensus: an odd-quorum Aeron Cluster replicates one committed
log into a deterministic ClusteredService hosting the inherited matching/risk core. Election,
log replication, commit, snapshotting, and member catch-up are consensus primitives; the
parent state's Lease election, NATS KV witness, custom MDC replication, and snapshot-bundle
recovery machinery are removed.

Parent lineage:

- parent state: `YU11-aeron-replication` (which renders onto `YU10-fix-ingress` ->
  `YU09-ops-hardening` -> `YU08-execution-algo-engine` -> `YU07-historical-tick-store` ->
  `YU06-eod-price-production` -> `YU05-post-trade-compliance` ->
  `YU04-durable-control-feeds` -> `YU03-in-memory-risk-gateway` ->
  `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

See `spec-source/spec.md` and `spec-source/system__runtime-topology.md` for the complete
runtime and failure contracts.
EOF
