#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU04-durable-control-feeds"
PARENT_STATE_ID="YU03-in-memory-risk-gateway"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU04-durable-control-feeds.sh"

cat <<'EOT'
[summary] state=YU04-durable-control-feeds
[summary] parent-state=YU03-in-memory-risk-gateway
[summary] impacted-components=order-matcher,account-service,reference-data
[summary] impacted-assets=control-feed-outbox-runtime-overrides,state-scaffold
[summary] generated-path=generated/code/target-generated/YU04-durable-control-feeds
[summary] runtime-entrypoint=inherits YU03-in-memory-risk-gateway runtime/deploy harness
EOT
