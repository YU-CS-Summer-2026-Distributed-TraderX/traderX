#!/usr/bin/env bash
# yu18-projection-effect-end.sh — ADR-080 / FR-DRS-02 on whatever feed mode the tier runs: one
# authenticated cross, followed to the effect end, and the projection's own completeness report.
#
#   1. a fresh ticker, seeded by the operator; a resting SELL from B and a crossing BUY from A
#   2. every member books exactly the two trade sides (members agree on `trades`)
#   3. the history store (ClickHouse `trades`, the trade system of record since the ADR-102 cutover) holds both
#      sides at the cross price; SQL `trades` holds none of them; SQL `positions` for A and B on the ticker equal the
#      signed sum of the history rows (an independent recomputation, not the row itself); and each account's blotter
#      (position-service GET /trades/<acct>) shows its side with the history store's own id
#   4. GET /v2/projection-feed reports backlog 0, dropped 0, missingTradeSides 0, and its applied
#      count moved by at least the two sides
#
# NEGATIVE=1 adds the control that makes step 4 mean something: with trade-processor scaled to 0 a
# second cross is booked by the engine and, on the core feed, never delivered (ADR-080 context:
# core NATS does not replay). After the projection is back, a third cross must surface that loss in
# missingTradeSides (FR-DRS-02: "count trade sides that never reached it"). It leaves the lost pair
# unprojected on the minted ticker, so run it last. On a jetstream-mode tier the stream replays
# them instead, and the arm asserts THAT: 6 of 6 sides, positions +15/-15 equal to the rows, nothing
# counted missing (FR-DRS-25).
#
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [NEGATIVE=1] bash scripts/proofs/yu18-projection-effect-end.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")

feed() { curl -s -m 10 "${TP}/v2/projection-feed"; }
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
place() { # place <acct> <side> <px> -> body; fails unless accepted
  local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":5,\"limitPrice\":$3}")"
  [[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "$2 $1 @$3 not accepted: ${c} $(body)"
}
cross() { place "${ACCT_B}" Sell "${PX}"; place "${ACCT_A}" Buy $((PX + 1)); }
sql_sides() { hist "SELECT count() FROM trades WHERE security='${T}'"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }
signed_sum() { hist "SELECT sum(if(side='B', toInt64(qty), -toInt64(qty))) FROM trades WHERE security='${T}' AND account=$1"; }
blotter() { # blotter <acct> -> "<id> <side> <qty> <price> <state>" per row of the account's on the ticker
  "${K[@]}" exec deploy/trade-processor -- curl -s -m 30 "http://position-service:18090/trades/$1" \
    | jget "'\\n'.join('%s %s %s %g %s' % (t['id'], t['side'], t['quantity'], t['price'], t['state']) for t in d if t['security']=='${T}')"; }
wait_sides() { local want="$1" i n; for i in $(seq 1 60); do n="$(sql_sides)"; [[ "${n}" == "${want}" ]] && return 0; sleep 1; done; echo "${n}"; return 1; }

step "0. preflight"
F0="$(feed)"; MODE="$(jget 'd["trades"]["mode"]' <<<"${F0}")"; [[ -n "${MODE}" ]] || fail "GET /v2/projection-feed did not answer the FR-DRS-02 shape: ${F0:0:200}"
A0="$(jget 'd["trades"]["applied"]' <<<"${F0}")"; T0="$(trades_all)"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-projection-effect-end' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
ok "feed mode ${MODE}/$(jget 'd["trades"].get("projection")' <<<"${F0}"), applied ${A0}; members' trades: ${T0}; seeded ${T} @${PX}"

step "1-2. one cross; every member books exactly two sides"
cross
for i in $(seq 1 30); do T1="$(trades_all)"; [[ "${T1}" != "${T0}" ]] && break; sleep 1; done
read -r -a t0 <<<"${T0}"; read -r -a t1 <<<"${T1}"
for ((m=0; m<MEMBERS; m++)); do [[ $(( t1[m] - t0[m] )) == 2 ]] || fail "member ${m} trades ${t0[m]} -> ${t1[m]} (want +2; another writer on the rig makes this inconclusive)"; done
ok "members' trades ${T0} -> ${T1}"

step "3. effect end: two history rows, none in SQL trades, positions = signed sum of the rows, the blotter shows them"
n="$(wait_sides 2)" || fail "history store trades for ${T}: ${n}, want 2"
px="$(hist "SELECT arrayStringConcat(groupUniqArray(toString(px)), ',') FROM trades WHERE security='${T}'")"
[[ "${px%%.*}" == "${PX}" ]] || fail "cross booked at ${px}, want ${PX} (the resting price)"
for i in $(seq 1 20); do [[ "$(pos "${ACCT_A}")" == 5 && "$(pos "${ACCT_B}")" == -5 ]] && break; sleep 1; done
for a in "${ACCT_A}" "${ACCT_B}"; do
  p="$(pos "${a}")"; s="$(signed_sum "${a}")"
  [[ "${p}" == "${s}" ]] || fail "account ${a}: positions ${p} != signed sum of its ${T} trades ${s}"
done
[[ "$(pos "${ACCT_A}")" == 5 && "$(pos "${ACCT_B}")" == -5 ]] || fail "positions A=$(pos "${ACCT_A}") B=$(pos "${ACCT_B}"), want +5/-5"
s="$(sql "SELECT COUNT(*) FROM trades WHERE security='${T}'")"
[[ "${s}" == 0 ]] || fail "SQL trades holds ${s} row(s) for ${T}: booked sides are the history store's alone (ADR-102 cutover)"
for a in "${ACCT_A}" "${ACCT_B}"; do
  want="$(hist "SELECT concat(id_prefix, toString(trade_seq), '-', side, ' ', if(side='B','Buy','Sell'), ' ', toString(qty)) FROM trades WHERE security='${T}' AND account=${a}")"
  got="$(blotter "${a}")"
  [[ -n "${want}" && "$(cut -d' ' -f1-3 <<<"${got}")" == "${want}" && "$(cut -d' ' -f4 <<<"${got}")" == "${PX}" ]] \
    || fail "account ${a}'s blotter on ${T}: '${got}', want '${want} ${PX} ...' (the history store's row)"
done
ok "2 history rows @${px}, 0 in SQL trades; positions ${ACCT_A}=+5 ${ACCT_B}=-5, each equal to its own trades' signed sum; blotters: $(blotter "${ACCT_A}" | cut -d' ' -f1,5) / $(blotter "${ACCT_B}" | cut -d' ' -f1,5)"

step "4. the projection reports itself complete"
for i in $(seq 1 20); do F1="$(feed)"; [[ "$(jget 'd["trades"]["backlog"]' <<<"${F1}")" == 0 ]] && break; sleep 1; done
for k in backlog dropped missingTradeSides; do
  v="$(jget "d['trades']['${k}']" <<<"${F1}")"; [[ "${v}" == 0 ]] || fail "projection-feed trades.${k}=${v}, want 0: ${F1}"
done
A1="$(jget 'd["trades"]["applied"]' <<<"${F1}")"
(( A1 - A0 >= 2 )) || fail "projection-feed trades.applied ${A0} -> ${A1}, want +2 or more"
ok "backlog 0, dropped 0, missingTradeSides 0; applied ${A0} -> ${A1}"

if [[ "${NEGATIVE:-0}" == 1 ]]; then
  step "5. NEGATIVE: a cross booked while the projection is down must be either replayed or counted missing"
  TPDEP="${TP_TARGET:-deploy/trade-processor}"
  restore() { "${K[@]}" scale "${TPDEP}" --replicas=1 >/dev/null 2>&1; }
  trap 'restore; _lib_yu18_cleanup' EXIT
  "${K[@]}" scale "${TPDEP}" --replicas=0 >/dev/null; "${K[@]}" wait --for=delete pod -l app=trade-processor --timeout=120s >/dev/null 2>&1
  cross; sleep 3
  restore; "${K[@]}" rollout status "${TPDEP}" --timeout=300s >/dev/null || fail "trade-processor did not come back"
  _lib_yu18_cleanup; PF_PIDS=(); pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"; pf TP "${TPDEP}" 18091
  cross
  sleep 10; F2="$(feed)"; n="$(sql_sides)"; miss="$(jget 'd["trades"]["missingTradeSides"]' <<<"${F2}")"
  echo "    after the outage: history sides on ${T}=${n} (engine booked 6); feed: ${F2}"
  if [[ "${n}" == 6 ]]; then
    for i in $(seq 1 20); do [[ "$(pos "${ACCT_A}")" == 15 && "$(pos "${ACCT_B}")" == -15 ]] && break; sleep 1; done
    for a in "${ACCT_A}" "${ACCT_B}"; do
      [[ "$(pos "${a}")" == "$(signed_sum "${a}")" ]] || fail "account ${a}: positions $(pos "${a}") != signed sum of its ${T} trades $(signed_sum "${a}")"
    done
    [[ "$(pos "${ACCT_A}")" == 15 && "$(pos "${ACCT_B}")" == -15 ]] || fail "after the replay positions A=$(pos "${ACCT_A}") B=$(pos "${ACCT_B}"), want +15/-15"
    [[ "${miss:-0}" == 0 ]] || fail "all 6 sides reached the store but missingTradeSides=${miss}: a replay counted as a loss"
    ok "the outage pair was replayed (mode ${MODE}): 6 of 6 sides in the history store, positions +15/-15 = the rows' signed sums, missingTradeSides 0"
  elif [[ "${n}" == 4 && "${miss:-0}" -ge 2 ]]; then ok "the outage pair is lost on the ${MODE} feed and COUNTED: missingTradeSides=${miss}"
  else fail "SQL has ${n} of 6 sides and missingTradeSides=${miss:-?}: trade sides never reached the projection and it does not say so (FR-DRS-02)"; fi
fi

echo; echo "YU18-PROJECTION-EFFECT-END PASS mode=${MODE} ticker=${T}"
