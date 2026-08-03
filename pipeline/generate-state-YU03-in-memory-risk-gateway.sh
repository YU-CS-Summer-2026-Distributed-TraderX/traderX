#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU03-in-memory-risk-gateway"
PARENT_STATE_ID="YU02-lmax-kubernetes"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU03-in-memory-risk-gateway.sh"

cat <<'EOT'
[summary] state=YU03-in-memory-risk-gateway
[summary] parent-state=YU02-lmax-kubernetes
[summary] impacted-components=order-matcher
[summary] impacted-assets=risk-gateway-runtime-overrides,state-scaffold
[summary] generated-path=generated/code/target-generated/YU03-in-memory-risk-gateway
[summary] runtime-entrypoint=inherits YU02-lmax-kubernetes runtime/deploy harness
EOT
