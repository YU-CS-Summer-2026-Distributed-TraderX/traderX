#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACK="${ROOT}/specs/YU18-eod-risk-bundles"
TARGET="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}/code/target-generated"
[[ -d "${TARGET}/YU17-otc-rates" ]] || { echo '[fail] generate parent YU17 first'; exit 1; }
mkdir -p "${TARGET}/eod-risk-bundles" "${TARGET}/YU18-eod-risk-bundles/spec-source"
tar -C "${PACK}/generation/runtime-overrides/eod-risk-bundles" --exclude='__pycache__' --exclude='*.pyc' -cf - . | tar -C "${TARGET}/eod-risk-bundles" -xf -
tar -C "${PACK}" --exclude='runtime-overrides' -cf - . | tar -C "${TARGET}/YU18-eod-risk-bundles/spec-source" -xf -
cp "${PACK}/README.md" "${TARGET}/YU18-eod-risk-bundles/README.md"
echo '[ok] rendered local EOD bundle component'
