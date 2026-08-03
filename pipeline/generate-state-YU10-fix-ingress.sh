#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU10-fix-ingress"
PARENT_STATE_ID="YU09-ops-hardening"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU10-fix-ingress.sh"

cat <<'EOT'
[summary] state=YU10-fix-ingress
[summary] parent-state=YU09-ops-hardening
[summary] impacted-components=order-matcher,kubernetes-runtime
[summary] impacted-assets=order-matcher-deployment,order-matcher-service,order-matcher-build-gradle,fix-package
[summary] generated-path=generated/code/target-generated/YU10-fix-ingress
[summary] runtime-entrypoint=inherits YU09-ops-hardening (=> YU08 => YU07 => YU06 => YU05 => YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
