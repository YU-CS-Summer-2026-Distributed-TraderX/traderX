#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU05-post-trade-compliance"
PARENT_STATE_ID="YU03-in-memory-risk-gateway"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU05-post-trade-compliance.sh"

cat <<'EOT'
[summary] state=YU05-post-trade-compliance
[summary] parent-state=YU03-in-memory-risk-gateway
[summary] impacted-components=order-matcher,trade-processor,kubernetes-runtime
[summary] impacted-assets=trade-blotter-recon-runtime-overrides,settlement-runtime-overrides,database-init-configmap,state-scaffold
[summary] generated-path=generated/code/target-generated/YU05-post-trade-compliance
[summary] runtime-entrypoint=inherits YU03-in-memory-risk-gateway runtime/deploy harness
EOT
