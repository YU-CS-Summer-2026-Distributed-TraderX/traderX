#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU07-historical-tick-store"
PARENT_STATE_ID="YU06-eod-price-production"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU07-historical-tick-store.sh"

cat <<'EOT'
[summary] state=YU07-historical-tick-store
[summary] parent-state=YU06-eod-price-production
[summary] impacted-components=tick-store,kubernetes-runtime
[summary] impacted-assets=tick-store-capture-runtime-overrides,tick-store-taq-ingestion-runtime-overrides,tick-store-deployment,tick-store-data-pvc,state-scaffold
[summary] generated-path=generated/code/target-generated/YU07-historical-tick-store
[summary] runtime-entrypoint=inherits YU06-eod-price-production (=> YU05 => YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
