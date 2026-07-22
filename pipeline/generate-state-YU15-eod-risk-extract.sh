#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU15-eod-risk-extract"
PARENT_STATE_ID="YU14-listed-equity-options"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU15-eod-risk-extract.sh"

cat <<'EOT'
[summary] state=YU15-eod-risk-extract
[summary] parent-state=YU14-listed-equity-options
[summary] impacted-components=order-matcher
[summary] impacted-assets=eod-risk-extract,sequenced-extract-marker,reproducible-fixture
[summary] generated-path=generated/code/target-generated/YU15-eod-risk-extract
[summary] runtime-entrypoint=inherits YU14-listed-equity-options (=> YU12 => YU11 => YU10 => ... => YU02 => 014) runtime/deploy harness
EOT
