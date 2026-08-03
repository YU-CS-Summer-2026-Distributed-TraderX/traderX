#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="YU13-limit-order-book"
PARENT_STATE_ID="YU12-aeron-cluster"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-YU13-limit-order-book.sh"

cat <<'EOT'
[summary] state=YU13-limit-order-book
[summary] parent-state=YU12-aeron-cluster
[summary] impacted-components=order-matcher
[summary] impacted-assets=crossing-limit-order-book,price-time-priority,book-snapshot-completeness
[summary] generated-path=generated/code/target-generated/YU13-limit-order-book
[summary] runtime-entrypoint=inherits YU12-aeron-cluster (=> YU11 => YU10 => ... => YU02 => 014) runtime/deploy harness
EOT
