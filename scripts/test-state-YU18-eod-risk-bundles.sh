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
mkdir -p "${DEMO}/inbox"
python3 "${COMPONENT}/bundle.py" build --positions "${COMPONENT}/tests/fixtures/exchange/positions.csv" --contracts "${COMPONENT}/tests/fixtures/exchange/contracts.csv" --epoch synthetic-exchange --valuation-time 2025-06-02T16:00:00-04:00 --origin synthetic --output "${DEMO}/inbox/cut"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/state" discover "${DEMO}/inbox" > "${DEMO}/discovery.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/state" run > "${DEMO}/run.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/state" discover "${DEMO}/inbox" > "${DEMO}/duplicate.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/state" run > "${DEMO}/repeat.json"
python3 - "${DEMO}" <<'PYDEMO'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
read = lambda name: json.loads((root / name).read_text())
assert len(read('discovery.json')['queued']) == 1
assert len(read('duplicate.json')['duplicates']) == 1
result = read('repeat.json')
assert result['processed'] == [] and len(result['jobs']) == 1
job = result['jobs'][0]
assert job['status'] == 'MOCK_COMPLETE' and len(job['attempts']) == 1
assert job['selectedMockResult'] and job['resultIntegrity'] == 'VERIFIED'
assert result['usableForRisk'] is False and job['usableForRisk'] is False
payload = json.loads((root / 'state' / job['result_path'] / 'results.json').read_text())
assert payload['coverage'] == {'submitted': 3, 'priced': 0}
assert all(item['npv'] is None for item in payload['items'])
PYDEMO
echo '[ok] local EOD bundle and durable coordinator demos complete; no financial valuation performed'
