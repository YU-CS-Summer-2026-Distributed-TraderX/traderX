#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="lmax-kubernetes"
PARENT_STATE_ID="014-fdc3-intent-interoperability"

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/render-state-lmax-kubernetes.sh"

cat <<'EOT'
[summary] state=lmax-kubernetes
[summary] parent-state=014-fdc3-intent-interoperability
[summary] impacted-assets=state-scaffold,forward-port-plan,runtime-overrides
[summary] generated-path=generated/code/target-generated/lmax-kubernetes
[summary] runtime-entrypoint=./scripts/start-state-lmax-kubernetes-generated.sh
[summary] runtime-status=overlay-in-progress
EOT
