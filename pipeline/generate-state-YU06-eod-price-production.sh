#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU06-eod-price-production"
PARENT_STATE_ID="YU05-post-trade-compliance"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU06-eod-price-production.sh"

cat <<'EOT'
[summary] state=YU06-eod-price-production
[summary] parent-state=YU05-post-trade-compliance
[summary] impacted-components=trade-processor,position-service,kubernetes-runtime
[summary] impacted-assets=eod-price-producer-runtime-overrides,eod-pnl-consumer-runtime-overrides,database-init-configmap,eod-session-close-cronjob,grafana-eod-dashboard,state-scaffold
[summary] generated-path=generated/code/target-generated/YU06-eod-price-production
[summary] runtime-entrypoint=inherits YU05-post-trade-compliance (=> YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
