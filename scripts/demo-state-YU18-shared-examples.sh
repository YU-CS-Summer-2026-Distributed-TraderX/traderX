#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}/code/target-generated"
COMPONENT="${GENERATED}/eod-risk-bundles"
MATCHER="${GENERATED}/order-matcher"
export PYTHONDONTWRITEBYTECODE=1
umask 077
[[ $# -eq 0 ]] || { echo 'Usage: demo-state-YU18-shared-examples.sh' >&2; exit 1; }
python3 - "${ROOT}" "${GENERATED}" <<'PY'
from pathlib import Path
import hashlib,json,sys
root,generated=map(Path,sys.argv[1:])
source=root/'specs/YU18-risk-integration/generation/runtime-overrides'
for p in source.rglob('*'):
    if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc':
        target=generated/p.relative_to(source)
        if not target.is_file() or target.read_bytes()!=p.read_bytes():
            raise SystemExit('Regenerate YU18; stale generated file: '+str(target))
proof=json.loads((generated/'eod-risk-bundles/tests/fixtures/shared/provenance.json').read_bytes())
for name,expected in proof['files'].items():
    if hashlib.sha256((generated/'order-matcher'/name).read_bytes()).hexdigest()!=expected:
        raise SystemExit('Exporter source changed; review shared fixture provenance: '+name)
PY
DEMO="$(mktemp -d /private/tmp/traderx-shared-examples.XXXXXX)"
export TRADERX_EOD_EXAMPLES_OUTPUT="${DEMO}/exports"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
(
  cd "${MATCHER}"
  ./gradlew --offline --no-daemon test --rerun-tasks --tests '*SharedEodExamplesTest' \
    --tests '*EodBundleExportTest' --tests '*RiskExtractTest' --tests '*SwapBookingTest'
) > "${DEMO}/exporter-tests.log" 2>&1 || { echo "Read ${DEMO}/exporter-tests.log" >&2; exit 1; }
python3 - "${MATCHER}/build/test-results/test" <<'PY'
from pathlib import Path
import sys,xml.etree.ElementTree as ET
for name in ('SharedEodExamplesTest','EodBundleExportTest','RiskExtractTest','SwapBookingTest'):
    files=list(Path(sys.argv[1]).glob('TEST-*.'+name+'.xml'))
    if len(files)!=1: raise SystemExit('Missing JUnit evidence: '+name)
    suite=ET.parse(files[0]).getroot()
    if int(suite.attrib['tests'])<(3 if name=='SharedEodExamplesTest' else 1) or any(int(suite.attrib.get(k,0)) for k in ('failures','errors','skipped')):
        raise SystemExit('JUnit suite did not pass: '+name)
PY
python3 "${COMPONENT}/shared_examples.py" --exports "${DEMO}/exports" --output "${DEMO}/shared"
python3 "${COMPONENT}/verify_golden.py"
python3 - "${COMPONENT}" "${DEMO}" <<'PY'
from pathlib import Path
import sys
component,root=map(Path,sys.argv[1:]);sys.path.insert(0,str(component))
import bundle
from coordinator import Coordinator
reference=component/'tests/fixtures/shared'
for name in ('bill','note','sofr'):
    expected=reference/name;actual=root/'shared'/name
    for p in expected.rglob('*'):
        if p.is_file():
            target=actual/p.relative_to(expected)
            bundle.require(target.is_file() and target.read_bytes()==p.read_bytes(),'fresh exporter differs from frozen example: '+str(p))
    # Each case contains v1 and v2 of the same cut. Keep consumer states separate, because
    # publishing both versions as concurrent candidates would correctly flag ambiguity.
    for version in ('v1','v2'):
        inbox=root/(name+'-'+version+'-inbox');inbox.mkdir()
        source=actual/version
        bundle.publish_directory(inbox/'cut',{p.name:p.read_bytes() for p in source.iterdir()})
        with Coordinator(root/(name+'-'+version+'-state')) as c:
            bundle.require(len(c.discover(inbox)['queued'])==1,'missing job')
            job=c.run()['jobs'][0]
            bundle.require(job['status']=='MOCK_COMPLETE' and job['resultIntegrity']=='VERIFIED' and job['selectedMockResult'],'invalid mock completion')
            bundle.require(not job['usableForRisk'] and len(job['attempts'])==1,'wrong attempt/risk status')
            bundle.require(len(c.discover(inbox)['duplicates'])==1,'duplicate discovery failed')
            bundle.require(c.run()['processed']==[],'duplicate run executed work')
print('PASS: 3 exporter-generated cases, frozen bytes reproduced, 6 versioned local mock runs, fixed v1 hashes.')
print('SOFR unsupported is an acceptance expectation for Alex; no external pricing was performed.')
print('Private evidence: '+str(root))
PY
