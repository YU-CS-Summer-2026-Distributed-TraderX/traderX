#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACK="${ROOT}/specs/YU18-risk-integration"
GENERATED="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}/code/target-generated"
MATCHER="${GENERATED}/order-matcher"
COMPONENT="${GENERATED}/eod-risk-bundles"
export PYTHONDONTWRITEBYTECODE=1
umask 077
[[ -f "${MATCHER}/gradlew" && -f "${COMPONENT}/bridge.py" ]] || {
  echo 'Generate YU18 first; see the state quickstart.' >&2; exit 1;
}
# Refuse a stale generated proof/helper/producer or Python bridge, instead of silently exercising it.
python3 - "${PACK}" "${GENERATED}" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]) / 'generation/runtime-overrides'
target = Path(sys.argv[2])
for file in source.rglob('*'):
    if file.is_file() and '__pycache__' not in file.parts and file.suffix != '.pyc':
        actual = target / file.relative_to(source)
        if not actual.is_file() or actual.read_bytes() != file.read_bytes():
            raise SystemExit('Generated source differs; regenerate YU18: ' + str(actual))
PY
if [[ $# -gt 1 ]]; then echo 'Usage: demo-state-YU18-eod-export.sh [NEW_PRIVATE_OUTPUT_DIRECTORY]' >&2; exit 1; fi
if [[ $# -eq 1 ]]; then
  [[ ! -e "$1" ]] || { echo 'Output directory must be new.' >&2; exit 1; }
  mkdir -m 700 -p "$1"
  DEMO="$(cd "$1" && pwd -P)"
else
  DEMO="$(mktemp -d /private/tmp/traderx-export-demo.XXXXXX)"
fi
python3 - "${DEMO}" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]).resolve()
if any((a/'.git').exists() for a in (p,*p.parents)):
    raise SystemExit('Demo artifacts must be outside Git checkouts.')
PY
echo "Private demo directory: ${DEMO}"
export TRADERX_EOD_DEMO_OUTPUT="${DEMO}/exports"
mkdir -m 700 "${TRADERX_EOD_DEMO_OUTPUT}"
# Caller can set JAVA_HOME; on macOS discover the installed Java 21 runtime.
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
(
  cd "${MATCHER}"
  ./gradlew --offline --no-daemon test --rerun-tasks --tests '*EodBundleExportTest' \
    --tests '*RiskExtractTest' --tests '*SwapBookingTest'
) > "${DEMO}/exporter-tests.log" 2>&1 || {
  echo "Exporter proof failed. Read ${DEMO}/exporter-tests.log" >&2; exit 1;
}
python3 - "${MATCHER}/build/test-results/test" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
for name in ('EodBundleExportTest','RiskExtractTest','SwapBookingTest'):
    paths=list(root.glob('TEST-*.'+name+'.xml'))
    assert len(paths)==1, name
    suite=ET.parse(paths[0]).getroot()
    assert int(suite.attrib['tests']) > 0 and all(int(suite.attrib.get(k,0))==0 for k in ('failures','errors','skipped')), name
PY
python3 "${COMPONENT}/bridge.py" --receipts "${DEMO}/exports/receipts" \
  --artifact-root "${DEMO}/exports" --inbox "${DEMO}/inbox" --epoch synthetic-export-demo \
  --session-date 2025-06-02 --valuation-time 2025-06-02T16:00:00-04:00 --origin synthetic > "${DEMO}/bridge.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/coordinator" discover "${DEMO}/inbox" > "${DEMO}/discovery.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/coordinator" run > "${DEMO}/result-status.json"
python3 "${COMPONENT}/bridge.py" --receipts "${DEMO}/exports/receipts" \
  --artifact-root "${DEMO}/exports" --inbox "${DEMO}/inbox" --epoch synthetic-export-demo \
  --session-date 2025-06-02 --valuation-time 2025-06-02T16:00:00-04:00 --origin synthetic > "${DEMO}/bridge-repeat.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/coordinator" discover "${DEMO}/inbox" > "${DEMO}/discovery-repeat.json"
python3 "${COMPONENT}/coordinator.py" --state "${DEMO}/coordinator" run > "${DEMO}/result-repeat.json"
python3 - "${DEMO}" <<'PY'
import json, sys
from pathlib import Path
root=Path(sys.argv[1]); read=lambda f: json.loads((root/f).read_text())
first=read('bridge.json'); assert len(first['packaged'])==1 and first['invalid']==[]
assert read('bridge-repeat.json')['packaged'][0]['duplicate'] is True
assert len(read('discovery-repeat.json')['duplicates'])==1
status=read('result-repeat.json'); assert len(status['jobs'])==1 and status['processed']==[]
job=status['jobs'][0]; assert job['status']=='MOCK_COMPLETE' and job['selectedMockResult']
assert job['usableForRisk'] is False and len(job['attempts'])==1
result=read('coordinator/'+job['result_path']+'/results.json')
assert result['coverage']=={'submitted':5,'priced':0}
assert sum(i['source']=='positions' for i in result['items'])==4
assert sum(i['source']=='contracts' for i in result['items'])==1
assert all(i['npv'] is None and i['status']=='NOT_PRICED' for i in result['items'])
source=Path(first['packaged'][0]['path'])
for f in ('positions.csv','contracts.csv'):
    assert (source/f).read_bytes()==(root/'exports'/f).read_bytes()
print('PASS: real in-process engine orders/bookings -> exporters -> completion receipt -> bundle -> coordinator -> mock.')
print('Four signed position rows, one SOFR contract; repeated delivery remains one job and one attempt.')
print('No live cluster/EOD-service orchestration or financial valuation was performed.')
print('Private artifacts: '+str(root))
PY
