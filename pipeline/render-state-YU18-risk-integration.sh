#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACK="${ROOT}/specs/YU18-risk-integration"
python3 "${ROOT}/scripts/check-state-components.py" "${PACK}"
TARGET="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}/code/target-generated"
[[ -d "${TARGET}/YU17-otc-rates" ]] || { echo '[fail] generate parent YU17 first'; exit 1; }
mkdir -p "${TARGET}/eod-risk-bundles" "${TARGET}/YU18-risk-integration/spec-source"
tar -C "${PACK}/generation/runtime-overrides/eod-risk-bundles" --exclude='__pycache__' --exclude='*.pyc' -cf - . | tar -C "${TARGET}/eod-risk-bundles" -xf -
tar -C "${PACK}" --exclude='runtime-overrides' --exclude='__pycache__' --exclude='*.pyc' -cf - . | tar -C "${TARGET}/YU18-risk-integration/spec-source" -xf -
# YU18's full-file overrides, last-wins over YU17: the exporter helper and producer override, and
# the order-types component (engine, gateways, read model, orderbook DDL).
for module in recovery-identity order-matcher trade-processor position-service postgres-database-replacement kubernetes-runtime; do
  if [[ -d "${PACK}/generation/runtime-overrides/${module}" ]]; then
    mkdir -p "${TARGET}/${module}"
    tar -C "${PACK}/generation/runtime-overrides/${module}" -cf - . | tar -C "${TARGET}/${module}" -xf -
  fi
done
cp "${PACK}/README.md" "${TARGET}/YU18-risk-integration/README.md"
echo '[ok] rendered local EOD bundle component'
