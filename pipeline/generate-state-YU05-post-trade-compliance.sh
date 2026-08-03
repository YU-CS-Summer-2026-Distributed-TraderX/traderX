#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU05-post-trade-compliance"
PARENT_STATE_ID="YU04-durable-control-feeds"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU05-post-trade-compliance.sh"

cat <<'EOT'
[summary] state=YU05-post-trade-compliance
[summary] parent-state=YU04-durable-control-feeds
[summary] impacted-components=order-matcher,trade-processor,kubernetes-runtime
[summary] impacted-assets=trade-blotter-recon-runtime-overrides,settlement-runtime-overrides,database-init-configmap,state-scaffold
[summary] generated-path=generated/code/target-generated/YU05-post-trade-compliance
[summary] runtime-entrypoint=inherits YU04-durable-control-feeds (=> YU03 => YU02 => 014) runtime/deploy harness
EOT
