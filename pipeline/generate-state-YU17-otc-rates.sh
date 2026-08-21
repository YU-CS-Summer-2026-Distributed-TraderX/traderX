#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU17-otc-rates"
PARENT_STATE_ID="YU16-cdm-instruments"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU17-otc-rates.sh"

cat <<'EOT'
[summary] state=YU17-otc-rates
[summary] parent-state=YU16-cdm-instruments
[summary] impacted-components=reference-data,price-publisher,order-matcher,trade-processor,position-service,trade-service,web-front-end,kubernetes-runtime
[summary] impacted-assets=cdm-instrument-model,treasury-pricing,fraction-of-par-convention,extract-schema-2
[summary] generated-path=generated/code/target-generated/YU17-otc-rates
[summary] runtime-entrypoint=inherits YU16-cdm-instruments (=> YU14 => YU13 => YU12 => ... => YU02 => 014) runtime/deploy harness
EOT
