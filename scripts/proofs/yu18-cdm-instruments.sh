#!/usr/bin/env bash
# yu18-cdm-instruments.sh — YU16 FR-CDM07/08/23/24 at the effect end, black-box.
#
#   1. FR-CDM07: reference-data's /instruments carries the five ETFs as Fund/ExchangeTradedFund and the
#      five Treasuries as Debt with the coupon, issue and maturity dates, original term, FIGI and
#      auction provenance the YU16 data-model tables state (the expected values are copied from
#      specs/YU16-cdm-instruments/data-model.md, not read from the system).
#   2. FR-CDM08: SPY trades through the inherited order path: every member books two sides, SQL holds
#      the rows, positions move by +5/-5, and position-service serves the SPY row with exactly the key
#      set of an equity row.
#   3. FR-CDM23/24 (DESTRUCTIVE: scales reference-data to 0 and back): with Treasury metadata
#      unavailable, a UST cross is booked by the engine but lands in SQL as two `Rejected` trades
#      carrying rejectionReason and sourceOrderId, with no position change. The control in the same
#      outage: an equity cross books normally (FR-CDM24: non-Treasury bookings do no metadata lookup).
#      After reference-data returns, the same UST cross books with positions +100/-100, so the
#      refusal was the outage and not the instrument.
#
# Run it late: the rejected UST pair stays in SQL while the engine holds those positions.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [DESTRUCTIVE=1] bash scripts/proofs/yu18-cdm-instruments.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf RD "${RD_TARGET:-svc/reference-data}" 18085
pf PS "${PS_TARGET:-svc/position-service}" 18090
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
OP=(-H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-cdm-instruments')
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
moved_by() { # moved_by <before> <n> — waits for every member's trades to move by exactly n
  local i a b m; read -r -a a <<<"$1"
  for i in $(seq 1 30); do read -r -a b <<<"$(trades_all)"; [[ $(( b[0] - a[0] )) -ge "$2" ]] && break; sleep 1; done
  for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == "$2" ]] || return 1; done
}
place() { # place <acct> <sym> <side> <qty> <px>
  local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"$2\",\"side\":\"$3\",\"quantity\":$4,\"limitPrice\":$5}")"
  [[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "$3 $1 $2 x$4 @$5 not accepted: ${c} $(body)"
}
cross() { place "${ACCT_B}" "$1" Sell "$2" "$3"; place "${ACCT_A}" "$1" Buy "$2" "$3"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='$1' AND accountid=$2"; }
rows() { sql "SELECT COUNT(*) FROM trades WHERE security='$1'${2:+ AND $2}"; }

step "1. FR-CDM07: the seed universe as the YU16 data model states it"
python3 - "${RD}" <<'PY' || fail "reference-data's /instruments disagrees with the YU16 data model (above)"
import json, sys, urllib.request
base = sys.argv[1]
etf = {'SPY': 'BBG000BDTBL9', 'QQQ': 'BBG000BSWKH7', 'IWM': 'BBG000CGC9C4', 'VTI': 'BBG000HR9779', 'GLD': 'BBG000CRF6Q8'}
ust = {  # key: (coupon %, issue, maturity, term, FIGI) — data-model.md "Seed universe additions"
    'UST-20280630': (4.125, '2026-06-30', '2028-06-30', 2, 'BBG022ZR1Z79'),
    'UST-20310630': (4.125, '2026-06-30', '2031-06-30', 5, 'BBG022ZR1Z51'),
    'UST-20360515': (4.375, '2026-05-15', '2036-05-15', 10, 'BBG0221YLR31'),
    'UST-20460515': (5.000, '2026-06-01', '2046-05-15', 20, 'BBG0226BZH97'),
    'UST-20560515': (5.000, '2026-05-15', '2056-05-15', 30, 'BBG0221YLR40')}
bad = []
get = lambda k: json.load(urllib.request.urlopen(f'{base}/instruments/{k}', timeout=10))
figis = lambda d: {i.get('identifier') for i in d.get('identifiers', []) if i.get('identifierType') == 'FIGI'}
for k, f in etf.items():
    d = get(k)
    if (d.get('securityType'), d.get('fundType'), d.get('assetClass')) != ('Fund', 'ExchangeTradedFund', 'ETF'): bad.append(f'{k}: {d.get("securityType")}/{d.get("fundType")}/{d.get("assetClass")}')
    if f not in figis(d): bad.append(f'{k}: FIGI {f} missing ({figis(d)})')
for k, (cpn, iss, mat, term, f) in ust.items():
    d = get(k); e = d.get('debtEconomics') or {}
    got = (d.get('securityType'), (e.get('fixedInterest') or {}).get('couponRatePercent'), e.get('issueDate'), e.get('maturityDate'), e.get('originalTermYears'), (e.get('priceProvenance') or {}).get('sourceType'))
    want = ('Debt', cpn, iss, mat, term, 'US_TREASURY_AUCTION_RESULT')
    if got[0] != want[0] or abs(float(got[1] or -1) - cpn) > 1e-9 or got[2:] != want[2:]: bad.append(f'{k}: got {got} want {want}')
    if f not in figis(d): bad.append(f'{k}: FIGI {f} missing')
for b in bad: print('    ', b)
print(f'    5 ETFs, 5 Treasuries checked; {len(bad)} disagreements')
sys.exit(1 if bad else 0)
PY
ok "5 ETFs Fund/ExchangeTradedFund, 5 Treasuries Debt with coupon/dates/term/FIGI/provenance as specified"

step "2. FR-CDM08: SPY through the inherited order, trade and position paths"
c="$(code -X POST "${GW}/seed" "${OP[@]}" -d "{\"accountId\":${ACCT_A},\"tickers\":\"SPY\",\"price\":100}")"; [[ "${c}" == 200 ]] || fail "operator /seed SPY: ${c} $(body)"
T0="$(trades_all)"; R0="$(rows SPY)"; PA0="$(pos SPY "${ACCT_A}")"; PB0="$(pos SPY "${ACCT_B}")"
cross SPY 5 100
moved_by "${T0}" 2 || fail "members' trades ${T0} -> $(trades_all), want +2 each"
for i in $(seq 1 30); do [[ "$(rows SPY)" == $(( R0 + 2 )) && "$(pos SPY "${ACCT_A}")" == $(( PA0 + 5 )) ]] && break; sleep 1; done
[[ "$(rows SPY)" == $(( R0 + 2 )) ]] || fail "SQL SPY trade rows ${R0} -> $(rows SPY), want +2"
[[ "$(pos SPY "${ACCT_A}")" == $(( PA0 + 5 )) && "$(pos SPY "${ACCT_B}")" == $(( PB0 - 5 )) ]] || fail "SPY positions A ${PA0}->$(pos SPY "${ACCT_A}") B ${PB0}->$(pos SPY "${ACCT_B}"), want +5/-5"
python3 - "${PS}" "${ACCT_A}" <<'PY' || fail "position-service's SPY row is not shaped like an equity row (above)"
import json, sys, urllib.request
rows = json.load(urllib.request.urlopen(f'{sys.argv[1]}/positions/{sys.argv[2]}', timeout=10))
spy = [r for r in rows if r.get('security') == 'SPY']; eq = [r for r in rows if r.get('security') not in ('SPY',) and not str(r.get('security', '')).startswith(('UST-', 'SW-', 'SWPT-')) and r.get('security', '').isalpha()]
if not spy or not eq: print(f'    SPY rows {len(spy)}, equity rows {len(eq)}'); sys.exit(1)
a, b = sorted(spy[0]), sorted(eq[0]); print(f'    SPY keys {a}; {eq[0]["security"]} keys {b}'); sys.exit(0 if a == b else 1)
PY
ok "members ${T0} -> $(trades_all); SPY rows +2; positions +5/-5; position row shape = equity's"

if [[ "${DESTRUCTIVE:-0}" != 1 ]]; then echo; echo "YU18-CDM-INSTRUMENTS PASS (steps 1-2; DESTRUCTIVE=1 for FR-CDM23/24)"; exit 0; fi

step "3. FR-CDM23/24: Treasury metadata unavailable -> Rejected trades, no position; equities unaffected"
UST="${UST:-UST-20280630}"; FR="0.998780"   # the 2Y and its stored seed fraction (data-model.md)
EQ="$(mint_ticker)"
c="$(code -X POST "${GW}/seed" "${OP[@]}" -d "{\"accountId\":${ACCT_A},\"tickers\":\"${EQ}\",\"price\":50}")"; [[ "${c}" == 200 ]] || fail "seed ${EQ}: ${c}"
restore() { "${K[@]}" scale deploy/reference-data --replicas=1 >/dev/null 2>&1; }
trap 'restore; _lib_yu18_cleanup' EXIT
"${K[@]}" scale deploy/reference-data --replicas=0 >/dev/null
"${K[@]}" wait --for=delete pod -l app=reference-data --timeout=120s >/dev/null 2>&1
[[ -z "$("${K[@]}" get endpoints reference-data -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null)" ]] || fail "reference-data still has endpoints"
T0="$(trades_all)"; RJ0="$(rows "${UST}" "state='Rejected'")"; UA0="$(pos "${UST}" "${ACCT_A}")"; UB0="$(pos "${UST}" "${ACCT_B}")"
cross "${UST}" 100 "${FR}"
moved_by "${T0}" 2 || fail "the engine did not book the UST cross: ${T0} -> $(trades_all)"
for i in $(seq 1 60); do [[ "$(rows "${UST}" "state='Rejected'")" == $(( RJ0 + 2 )) ]] && break; sleep 1; done
[[ "$(rows "${UST}" "state='Rejected'")" == $(( RJ0 + 2 )) ]] || fail "SQL Rejected ${UST} rows ${RJ0} -> $(rows "${UST}" "state='Rejected'") 60 s into the outage (FR-CDM23: fail closed to Rejected)"
blank="$(sql "SELECT COUNT(*) FROM trades WHERE security='${UST}' AND state='Rejected' AND (COALESCE(rejectionreason,'')='' OR COALESCE(sourceorderid,'')='')")"
[[ "${blank}" == 0 ]] || fail "${blank} Rejected ${UST} rows lack rejectionReason or sourceOrderId"
sleep 3; [[ "$(pos "${UST}" "${ACCT_A}")" == "${UA0}" && "$(pos "${UST}" "${ACCT_B}")" == "${UB0}" ]] || fail "a Rejected booking moved positions: A ${UA0}->$(pos "${UST}" "${ACCT_A}") B ${UB0}->$(pos "${UST}" "${ACCT_B}")"
why="$(sql "SELECT rejectionreason FROM trades WHERE security='${UST}' AND state='Rejected' ORDER BY id DESC LIMIT 1")"
ok "UST cross booked by the engine, 2 Rejected rows (\"${why:0:90}\"), positions unchanged"
T0="$(trades_all)"; cross "${EQ}" 5 50
moved_by "${T0}" 2 || fail "the control equity cross did not book"
for i in $(seq 1 30); do [[ "$(pos "${EQ}" "${ACCT_A}")" == 5 ]] && break; sleep 1; done
[[ "$(pos "${EQ}" "${ACCT_A}")" == 5 && "$(pos "${EQ}" "${ACCT_B}")" == -5 && "$(rows "${EQ}" "state='Rejected'")" == 0 ]] || fail "with reference-data down the equity ${EQ} booked A=$(pos "${EQ}" "${ACCT_A}") B=$(pos "${EQ}" "${ACCT_B}") rejected=$(rows "${EQ}" "state='Rejected'") (FR-CDM24: no metadata lookup for non-Treasury)"
ok "control: equity ${EQ} books +5/-5 in the same outage"
restore; "${K[@]}" rollout status deploy/reference-data --timeout=300s >/dev/null || fail "reference-data did not come back"
for i in $(seq 1 30); do curl -s -m 3 -o /dev/null "${RD}/instruments/${UST}" 2>/dev/null && break; _lib_yu18_cleanup; PF_PIDS=(); pf RD svc/reference-data 18085; pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"; pf TP "${TP_TARGET:-deploy/trade-processor}" 18091; done
T0="$(trades_all)"; cross "${UST}" 100 "${FR}"
moved_by "${T0}" 2 || fail "the post-outage UST cross did not book"
for i in $(seq 1 60); do [[ "$(pos "${UST}" "${ACCT_A}")" == $(( UA0 + 100 )) ]] && break; sleep 1; done
[[ "$(pos "${UST}" "${ACCT_A}")" == $(( UA0 + 100 )) && "$(pos "${UST}" "${ACCT_B}")" == $(( UB0 - 100 )) ]] || fail "after reference-data returned, the UST cross moved positions A ${UA0}->$(pos "${UST}" "${ACCT_A}") B ${UB0}->$(pos "${UST}" "${ACCT_B}") (want +100/-100)"
ok "reference-data back: the same UST cross books +100/-100"

echo; echo "YU18-CDM-INSTRUMENTS PASS"
