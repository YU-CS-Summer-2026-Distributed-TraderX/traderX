#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_SPEC_DIR="${ROOT}/specs/YU09-ops-hardening"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
PARENT_STATE_DIR="${TARGET_ROOT}/YU08-execution-algo-engine"
STATE_DIR="${TARGET_ROOT}/YU09-ops-hardening"
SPEC_SOURCE_DIR="${STATE_DIR}/spec-source"
RUNTIME_OVERRIDES_DIR="${STATE_SPEC_DIR}/generation/runtime-overrides"

[[ -d "${PARENT_STATE_DIR}" ]] || {
  echo "[fail] required parent YU08-execution-algo-engine artifact missing for YU09 render: ${PARENT_STATE_DIR}"
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
    echo "[info] no ${label} overrides present (${src}); keeping YU08-execution-algo-engine parity"
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
  system/dr-runbook.md \
  system/adr-032-journal-rotation-and-gcs-archival.md \
  system/adr-033-secrets-via-out-of-band-kubectl-secrets.md \
  tasks.md \
  generation/generation-hook.md \
  generation/implementation-status.md; do
  src_path="${STATE_SPEC_DIR}/${source}"
  [[ -f "${src_path}" ]] || continue
  target_name="${source//\//__}"
  cp "${src_path}" "${SPEC_SOURCE_DIR}/${target_name}"
done

cat > "${STATE_DIR}/README.md" <<'EOF'
# YU09-ops-hardening Generated Artifacts

Ops hardening: database and JWT/dev-token credentials moved to Kubernetes Secrets (created
out-of-band, never committed); the order-matcher journal rotates at snapshot boundaries and
archives closed segments to GCS when enabled (off by default); the shared build pipeline always
rebuilds a fresh jar before the Docker build; a DR runbook documents the cluster's actual
single-zone failure modes.

Parent lineage:

- parent state: `YU08-execution-algo-engine` (which renders onto `YU07-historical-tick-store` ->
  `YU06-eod-price-production` -> `YU05-post-trade-compliance` -> `YU04-durable-control-feeds` ->
  `YU03-in-memory-risk-gateway` -> `YU02-lmax-kubernetes` -> `014-fdc3-intent-interoperability`)

No new component or Deployment. Changes are Secret-sourced credentials on existing manifests, new
`JournalArchiver`/`Journaler` rotation logic in `order-matcher`, and a pipeline fix in
`publish-generated-state-branch.sh`; everything else is inherited unchanged from
`YU08-execution-algo-engine` (see `spec-source/spec.md` for scope).
EOF
