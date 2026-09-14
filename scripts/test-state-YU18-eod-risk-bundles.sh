#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPONENT="${1:-${ROOT}/specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles}"
if [[ $# -eq 0 && -d "${ROOT}/eod-risk-bundles" ]]; then
  COMPONENT="${ROOT}/eod-risk-bundles"
fi
export PYTHONDONTWRITEBYTECODE=1
python3 -m unittest discover -s "${COMPONENT}/tests" -v
DEMO="$(mktemp -d "${TMPDIR:-/tmp}/yu18-smoke.XXXXXX")"
trap 'rm -rf "${DEMO}"' EXIT
python3 "${COMPONENT}/bundle.py" build --positions "${COMPONENT}/tests/fixtures/positions.csv" --contracts "${COMPONENT}/tests/fixtures/contracts.csv" --epoch synthetic-demo --valuation-time 2025-06-02T16:00:00-04:00 --origin synthetic --output "${DEMO}/bundle"
python3 "${COMPONENT}/bundle.py" validate "${DEMO}/bundle"
python3 "${COMPONENT}/bundle.py" mock "${DEMO}/bundle" --output "${DEMO}/result"
echo '[ok] local EOD transport demo complete; no financial valuation performed'
