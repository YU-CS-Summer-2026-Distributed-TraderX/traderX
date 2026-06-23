#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="in-memory-risk-gateway"
PARENT_STATE_ID="009b-lmax-sequencer-architecture"
PATCH_DIR="${ROOT}/specs/${STATE_ID}/generation/patches"

if (( ${TRADERX_GENERATION_DEPTH:-0} == 0 )); then
  echo "[info] delegating direct invocation to pipeline/generate-state.sh ${STATE_ID}"
  exec bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
fi

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"

if compgen -G "${PATCH_DIR}/*.patch" >/dev/null; then
  bash "${ROOT}/pipeline/apply-state-patchset.sh" "${STATE_ID}"
else
  echo "[info] no ${STATE_ID} patchset yet; applying runtime overrides to generated ${PARENT_STATE_ID}"
fi

bash "${ROOT}/pipeline/render-state-in-memory-risk-gateway.sh"
bash "${ROOT}/pipeline/generate-state-architecture-doc.sh" "${STATE_ID}"

cat <<'EOT'
[summary] state=in-memory-risk-gateway
[summary] parent-state=009b-lmax-sequencer-architecture
[summary] track=architecture
[summary] impacted-assets=gateway-replicas,pre-trade-risk,blp-reservations,idempotency,control-events,risk-observability
[summary] generated-path=generated/code/target-generated/order-matcher
[summary] runtime-entrypoint=./scripts/start-state-in-memory-risk-gateway-generated.sh
EOT
