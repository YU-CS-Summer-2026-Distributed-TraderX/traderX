#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="009b-lmax-sequencer-architecture"
PARENT_STATE_ID="009-order-management-matcher"
PATCH_DIR="${ROOT}/specs/${STATE_ID}/generation/patches"

# This file is a state hook used by pipeline/generate-state.sh. When invoked
# directly, delegate to the canonical entrypoint so post-generation installers
# (runtime harness/env wrappers/CI assets/docs refresh) run for state 009b.
if (( ${TRADERX_GENERATION_DEPTH:-0} == 0 )); then
  echo "[info] delegating direct invocation to pipeline/generate-state.sh ${STATE_ID}"
  exec bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
fi

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"

# The LMAX hot-path overlay is captured from implemented deltas via
# pipeline/create-state-patchset.sh (tasks T09B11..T09B17). Until that patchset
# exists this state generates a 009-identical runtime with 009b state identity,
# so the pipeline stays runnable end-to-end during spec-first development.
if compgen -G "${PATCH_DIR}/*.patch" >/dev/null; then
  bash "${ROOT}/pipeline/apply-state-patchset.sh" "${STATE_ID}"
else
  echo "[warn] no patchset found in ${PATCH_DIR}; generating 009-parity output"
  echo "[hint] capture the overlay with: bash pipeline/create-state-patchset.sh ${STATE_ID} ${PARENT_STATE_ID}"
fi

bash "${ROOT}/pipeline/render-state-009b-lmax-sequencer-architecture.sh"
bash "${ROOT}/pipeline/generate-state-architecture-doc.sh" "${STATE_ID}"

cat <<'EOT'
[summary] state=009b-lmax-sequencer-architecture
[summary] parent-state=009-order-management-matcher
[summary] track=architecture
[summary] impacted-assets=order-matcher-lmax-hot-path,input-disruptor,blp,output-disruptor,journal,read-model-projector,no-gc-gate,hot-path-observability
[summary] generated-path=generated/code/target-generated/order-management-matcher
[summary] runtime-entrypoint=./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh
EOT
