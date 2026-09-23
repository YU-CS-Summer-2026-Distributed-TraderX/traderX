#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "${ROOT}/pipeline/generate-state.sh" YU17-otc-rates
bash "${ROOT}/pipeline/generate-state-architecture-doc.sh" YU18-risk-integration
bash "${ROOT}/pipeline/render-state-YU18-risk-integration.sh"
echo '[summary] state=YU18-risk-integration parent=YU17-otc-rates component=eod-risk-bundles runtime=local-python-cli'
