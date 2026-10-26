#!/usr/bin/env bash
# yu18-var-es-e2e.sh — Alex's request: "a test of at least VaR and ES working end to end on each
# instrument." Both halves, back to back, locally.
#
#   TraderX half   VarEsEndToEndTest (the generated order-matcher suite): every instrument class
#                  TraderX books (listed equity over REST, listed option over FIX, Treasury bill over
#                  the binary fast path, OTC swaps over REST) through the REAL gateway (a child JVM)
#                  into three real members; venue close; the sequenced EOD marker at N; the cut
#                  rendered byte-identically on every member; the production exporters' write-once
#                  fixture. Negative control: a counterparties table missing one account REFUSES.
#   engine half    risk-var-es-e2e.py: eod-risk-bundles packages the receipt; every row is mapped
#                  to Alex's upstream engine's POST /runs (analytics npv + market_risk) or REFUSED
#                  by name; VaR and ES must be finite and non-zero for the portfolio and attributed
#                  to every trade from the inline P&L; a market missing a curve must be refused (400).
#
# Exit 0 only when every instrument in the fixture carries finite non-zero VaR and ES. Today Alex's
# f9cc4c1 has no equity or listed-option product and implements only ACT/365 and ACT/ACT (ICMA) day
# counts, so the equity, option and USD-SOFR (ACT/360) legs are reported as refused, by name, in
# report.json; the Treasury bill and the GBP-SONIA (ACT/365F) swap price. That is the finding this
# proof exists to make visible, not a reason to hide it.
#
# Usage:
#   RISK_ENGINE_ORIGIN=http://127.0.0.1:8765 scripts/proofs/yu18-var-es-e2e.sh [NEW_PRIVATE_OUTPUT_DIR]
# Needs: a generated YU18 tree (pipeline/generate-state.sh YU18-risk-integration), JDK 21, and the
# engine running at RISK_ENGINE_ORIGIN (docs/risk-integration/local-cpu-loop.md: uv venv + uvicorn
# engine.api.app:app). The output directory must be new and outside Git.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATED="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}/code/target-generated"
MATCHER="${GENERATED}/order-matcher"
ORIGIN="${RISK_ENGINE_ORIGIN:-http://127.0.0.1:8765}"
export PYTHONDONTWRITEBYTECODE=1
umask 077

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

[[ -f "${MATCHER}/gradlew" ]] || fail "no generated order-matcher at ${MATCHER}; run pipeline/generate-state.sh YU18-risk-integration"
[[ $# -le 1 ]] || fail "usage: $0 [NEW_PRIVATE_OUTPUT_DIR]"
if [[ $# -eq 1 ]]; then
  [[ ! -e "$1" ]] || fail "output directory must be new: $1"
  mkdir -m 700 -p "$1"; OUT="$(cd "$1" && pwd -P)"
else
  OUT="$(mktemp -d /private/tmp/traderx-var-es-e2e.XXXXXX)"
fi
python3 - "${OUT}" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1]).resolve()
if any((a / '.git').exists() for a in (p, *p.parents)):
    raise SystemExit('proof artifacts must be outside Git checkouts')
PY
echo "Private output directory: ${OUT}"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

step "0. preflight: the engine answers /health; no other suite holds the cluster test ports"
curl -sf --max-time 10 "${ORIGIN}/health" >/dev/null || fail "risk engine not answering at ${ORIGIN}/health"
if pgrep -f 'Gradle Test Executor' >/dev/null 2>&1; then
  echo "[warn] another Gradle test executor is running on this machine; cluster tests bind fixed UDP ports (24800+ here)"
fi

step "1. TraderX half: VarEsEndToEndTest -> ${OUT}/traderx"
mkdir -m 700 "${OUT}/traderx"
( cd "${MATCHER}" && TRADERX_VAR_ES_E2E_OUTPUT="${OUT}/traderx" \
    ./gradlew --offline --no-daemon test --rerun-tasks --tests '*VarEsEndToEndTest' ) > "${OUT}/traderx-test.log" 2>&1 \
  || { tail -40 "${OUT}/traderx-test.log" >&2; fail "TraderX half failed; read ${OUT}/traderx-test.log"; }
python3 - "${MATCHER}/build/test-results/test" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
paths = list(Path(sys.argv[1]).glob('TEST-*.VarEsEndToEndTest.xml'))
assert len(paths) == 1, paths
r = ET.parse(paths[0]).getroot()
assert r.get('tests') == '1' and r.get('failures') == '0' and r.get('errors') == '0' and r.get('skipped') == '0', r.attrib
out = r.find('system-out').text or ''
line = next((l for l in out.splitlines() if l.startswith('VAR-ES-E2E-TRADERX ')), None)
assert line, 'no VAR-ES-E2E-TRADERX line'
print(line)
PY
[[ -s "${OUT}/traderx/positions.csv" && -s "${OUT}/traderx/contracts.csv" ]] || fail "fixture not written under ${OUT}/traderx"
ls "${OUT}/traderx/receipts"/*.ready.json >/dev/null 2>&1 || fail "no ready receipt under ${OUT}/traderx/receipts"

step "2. engine half: bundle -> POST /runs (npv + market_risk) -> VaR/ES per instrument -> ${OUT}/engine"
python3 "${ROOT}/scripts/risk-var-es-e2e.py" --artifacts "${OUT}/traderx" --origin "${ORIGIN}" --evidence "${OUT}/engine" \
  > "${OUT}/engine-report.txt" 2>&1 && VERDICT=0 || VERDICT=$?
python3 - "${OUT}/engine/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print('status   ', r['status'])
print('bundle   ', r.get('bundle'))
print('portfolio', (r.get('run') or {}).get('risk'))
for k, v in sorted((r.get('attribution') or {}).items()):
    print('  priced ', v.get('class'), v.get('security') or v.get('contractId'), {q: v[q] for q in v if q.startswith(('VaR', 'ES'))})
for x in r.get('refused') or []:
    print('  REFUSED', x['class'], x['identity'], '-', x['reason'])
print('negative ', r.get('negative_control', {}).get('status'), 'expected 400')
print('coverage ', r.get('coverage'))
PY
echo
if [[ "${VERDICT}" -eq 0 ]]; then
  echo "VAR-ES-E2E PASSED: every instrument in the fixture has finite non-zero VaR and ES (${OUT}/engine/report.json)"
else
  echo "VAR-ES-E2E FAILED: see ${OUT}/engine/report.json (problems[] names each instrument the engine refused)" >&2
  exit "${VERDICT}"
fi
