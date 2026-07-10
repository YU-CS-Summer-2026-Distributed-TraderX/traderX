#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU08-execution-algo-engine"
PARENT_STATE_ID="YU07-historical-tick-store"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU08-execution-algo-engine.sh"

cat <<'EOT'
[summary] state=YU08-execution-algo-engine
[summary] parent-state=YU07-historical-tick-store
[summary] impacted-components=execution-algo-engine,kubernetes-runtime
[summary] impacted-assets=execution-algo-engine-service,execution-algo-engine-deployment,execution-algo-engine-service-manifest,state-scaffold
[summary] generated-path=generated/code/target-generated/YU08-execution-algo-engine
[summary] runtime-entrypoint=inherits YU07-historical-tick-store (=> YU06 => YU05 => YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
