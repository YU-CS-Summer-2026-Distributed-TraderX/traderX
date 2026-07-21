#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU14-listed-equity-options"
PARENT_STATE_ID="YU13-limit-order-book"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU14-listed-equity-options.sh"

cat <<'EOT'
[summary] state=YU14-listed-equity-options
[summary] parent-state=YU13-limit-order-book
[summary] impacted-components=order-matcher
[summary] impacted-assets=listed-equity-options,occ-instrument-model,multiplier-aware-notional
[summary] generated-path=generated/code/target-generated/YU14-listed-equity-options
[summary] runtime-entrypoint=inherits YU13-limit-order-book (=> YU12 => YU11 => YU10 => ... => YU02 => 014) runtime/deploy harness
EOT
