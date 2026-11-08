#!/usr/bin/env bash
# ADR-102 cutover at the effect end, over everything the rig booked: three stores, one answer.
#   extract   the last RISK-EXTRACT-READY file (the engine's cut, ADR-079 §5) per (account, security)
#   history   ClickHouse trades, the trade system of record: sum of signed qty per (account, security)
#   SQL       positions per (account, security) on the traded securities = the history less the sides booking refused
#             (FR-DRS-19: an SQL Rejected exception row moves no read-model position; the engine, and so the extract, kept it); SQL trades holds no booked side (only Rejected / forced Settled)
#   blotter   each account's position-service GET /trades/<acct> rows = its newest history rows (ids, newest first)
# Run after yu18-extract-off-projection.sh (a published session) with no writer since.
#   CTX=kind-<c> NS=traderx bash scripts/trade-history-tiers/kind-cutover-check.sh
set -euo pipefail
here="$(cd "$(dirname "$0")/../proofs" && pwd)"
. "${here}/lib-yu18.sh"
"${K[@]}" rollout status deploy/position-service --timeout=300s >/dev/null || fail "position-service (the blotter) is not Ready"
XPOD="$("${K[@]}" get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}')"; [[ -n "${XPOD}" ]] || fail "no risk-extract pod"
URI="$("${K[@]}" logs "${XPOD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^.*RISK-EXTRACT-READY //' | jget 'd.get("uri")')"
[[ "${URI}" == file://* ]] || fail "no file:// extract announced (run yu18-extract-off-projection.sh first): '${URI}'"
ext="$("${K[@]}" exec "${XPOD}" -- cat "${URI#file://}" | python3 -c "
import sys,csv,io
r=csv.DictReader(io.StringIO('\n'.join(l for l in sys.stdin.read().splitlines() if l and not l.startswith('#'))))
for row in sorted(r, key=lambda x: (int(x['accountId']), x['security'])):
  q=int(float(row['quantity']))
  if q: print(row['accountId'], row['security'], q)")"
rej="$(sql "SELECT id FROM trades WHERE state='Rejected'" | sed "s/.*/'&'/" | paste -sd, -)"
net(){ hist "SELECT account, security, s FROM (SELECT account, security, sum(if(side='B', toInt64(qty), -toInt64(qty))) s FROM trades WHERE $1 GROUP BY account, security) WHERE s != 0 ORDER BY account, security FORMAT TSV" | tr '\t' ' '; }
his="$(net 1)"; kept="$(net "concat(id_prefix, toString(trade_seq), '-', side) NOT IN (${rej:-''})")"
# only traded accounts and securities: the shipped schema seeds fixture positions no trade made (and no extract carries)
secs="$(hist "SELECT DISTINCT security FROM trades" | sed "s/.*/'&'/" | paste -sd, -)"
accts="$(hist "SELECT DISTINCT account FROM trades" | paste -sd, -)"
pos="$(sql "SELECT accountid, security, quantity FROM positions WHERE quantity <> 0 AND security IN (${secs:-''}) AND accountid IN (${accts:-0}) ORDER BY accountid, security" | tr '\t' ' ')"
[[ -n "${his}" ]] || fail "the history store holds no position-bearing side: cross some first"
[[ "${ext}" == "${his}" ]] || fail "extract and history store differ:$(diff <(echo "${ext}") <(echo "${his}") | sed 's/^/ /')"
[[ "${pos}" == "${kept}" ]] || fail "SQL positions and the history store less Rejected sides differ:$(diff <(echo "${pos}") <(echo "${kept}") | sed 's/^/ /')"
ok "extract ${URI##*/} = history store ($(wc -l <<<"${his}" | tr -d ' ') (account, security) rows); SQL positions = history less $(grep -c . <<<"${rej//,/$'\n'}") Rejected side(s) ($(wc -l <<<"${kept}" | tr -d ' ') rows)"
booked="$(sql "SELECT COUNT(*) FROM trades WHERE state NOT IN ('Rejected','Settled')")"
[[ "${booked}" == 0 ]] || fail "SQL trades holds ${booked} booked (non-exception) row(s): the cutover still writes them"
n=0
for a in $(hist "SELECT DISTINCT account FROM trades ORDER BY account"); do
  want="$(hist "SELECT concat(id_prefix, toString(trade_seq), '-', side) FROM trades WHERE account=${a} ORDER BY ts DESC, seq DESC LIMIT 100")"
  got="$("${K[@]}" exec deploy/trade-processor -- curl -s -m 30 "http://position-service:18090/trades/${a}" | jget "'\\n'.join(t['id'] for t in d)")"
  [[ "${got}" == "${want}" ]] || fail "account ${a}'s blotter is not its newest history rows:$(diff <(echo "${got}") <(echo "${want}") | head -6 | sed 's/^/ /')"
  n=$((n + $(grep -c . <<<"${want}")))
done
ok "SQL trades: 0 booked rows; every account's blotter = its newest history rows (${n} rows)"
echo; echo "KIND-CUTOVER-CHECK PASS"
