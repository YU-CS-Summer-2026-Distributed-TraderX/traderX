#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU11-aeron-replication"
PARENT_STATE_ID="YU10-fix-ingress"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU11-aeron-replication.sh"

cat <<'EOT'
[summary] state=YU11-aeron-replication
[summary] parent-state=YU10-fix-ingress
[summary] impacted-components=order-matcher,aeron-replication-sidecar,kubernetes-runtime
[summary] impacted-assets=sbe-schema,aeron-transport,archive-sidecar,replication-policy,failover-witness,runtime-manifests,bench-proof
[summary] generated-path=generated/code/target-generated/YU11-aeron-replication
[summary] runtime-entrypoint=inherits YU10-fix-ingress (=> YU09 => YU08 => YU07 => YU06 => YU05 => YU04 => YU03 => YU02 => 014) runtime/deploy harness
EOT
