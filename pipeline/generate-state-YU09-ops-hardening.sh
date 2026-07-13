#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU09-ops-hardening"
PARENT_STATE_ID="YU08-execution-algo-engine"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU09-ops-hardening.sh"

cat <<'EOT'
[summary] state=YU09-ops-hardening
[summary] parent-state=YU08-execution-algo-engine
[summary] impacted-components=order-matcher,trade-processor,account-service,position-service,kubernetes-runtime
[summary] impacted-assets=database-deployment,order-matcher-deployment,trade-processor-deployment,account-service-deployment,position-service-deployment,Journaler,JournalArchiver,LmaxEngine,order-matcher-build-gradle,publish-generated-state-branch-script
[summary] generated-path=generated/code/target-generated/YU09-ops-hardening
[summary] runtime-entrypoint=inherits YU08-execution-algo-engine (=> YU07 => YU06 => YU05 => YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
