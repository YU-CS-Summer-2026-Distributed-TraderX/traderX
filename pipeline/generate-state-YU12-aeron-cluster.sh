#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU12-aeron-cluster"
PARENT_STATE_ID="YU11-aeron-replication"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU12-aeron-cluster.sh"

cat <<'EOT'
[summary] state=YU12-aeron-cluster
[summary] parent-state=YU11-aeron-replication
[summary] impacted-components=order-matcher
[summary] impacted-assets=aeron-cluster-consensus,clustered-service-hosting,cluster-snapshot-completeness
[summary] generated-path=generated/code/target-generated/YU12-aeron-cluster
[summary] runtime-entrypoint=inherits YU11-aeron-replication (=> YU10 => YU09 => ... => YU02 => 014) runtime/deploy harness
EOT
