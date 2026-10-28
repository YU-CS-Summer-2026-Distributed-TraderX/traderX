#!/usr/bin/env bash
# yu18-market-order.sh — YU13 FR-LOB04/05 (and the contract-delta matching table), black-box, at the
# effect end on a fresh ticker:
#
#   1. FR-LOB04 refusal: with no trade printed and an empty book, a market Buy (no limitPrice) is
#      refused PRICE_MISSING and books nothing. The ticker is seeded first, so this also pins that a
#      tick-seeded mark is NOT the "last trade price" a market order is validated at (FR-LOB04/05).
#   2. FR-LOB04 fallback + never rests: a resting Sell 5 @50, then a market Buy 8. It is validated
#      at the opposite best, fills 5 @50 (the resting price) and its remainder 3 is cancelled in
#      place: SQL holds the two sides, positions are +5 / -5, and the market order is not open.
#   3. control for "never rests": a later limit Sell 3 @50 RESTS, with no trade. Had the market
#      remainder rested as a Buy @market it would have crossed it.
#   4. FR-LOB05: a resting Buy 2 @48, then the operator re-seeds the price at 45 (a tick through the
#      Buy's price): no trade on any member, and the Buy is still open.
#
# The REST ack shape is not specified (COVERAGE.md spec gap 3); the proof reads `kind`/`reason` for
# the log only and asserts on members and SQL.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] bash scripts/proofs/yu18-market-order.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
order() { # order <acct> <side> <qty> [limit] -> sets C and R (body)
  local lp=""; [[ -n "${4:-}" ]] && lp=",\"limitPrice\":$4"
  C="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":$3${lp}}")"; R="$(body)"
  echo "    $2 $1 x$3 ${4:-MARKET} -> ${C} ${R:0:160}"
}
ref() { jget 'd.get("orderRef")' <<<"${R}"; }
status_of() { sql "SELECT status FROM orderbook WHERE security='${T}' AND orderid LIKE '%-$1'"; }
sides() { sql "SELECT COUNT(*) FROM trades WHERE security='${T}'"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }
settle() { local want="$1" i; for i in $(seq 1 30); do [[ "$(sides)" == "${want}" ]] && return 0; sleep 1; done; return 1; }
same_trades() { sleep 4; [[ "$(trades_all)" == "$1" ]]; }

step "0. seed ${T} @${PX} (a tick, no trade)"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-market-order' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
ok "seeded; members' trades $(trades_all)"

step "1. FR-LOB04: no last trade, no opposite best -> PRICE_MISSING, nothing booked"
T0="$(trades_all)"
order "${ACCT_A}" Buy 4
same_trades "${T0}" || fail "the market order on an empty book moved the members' trades ${T0} -> $(trades_all)"
[[ "$(sql "SELECT COUNT(*) FROM orderbook WHERE security='${T}' AND status IN ('NEW','PARTIALLY_FILLED')")" == 0 ]] || fail "a market order on an empty book is open in SQL: it rested"
# Recorded, not fatal: steps 2-4 are independent of it.
if [[ "${R}" == *PRICE_MISSING* ]]; then ok "PRICE_MISSING; members ${T0} unchanged; nothing open"
else SUSPECT=1; echo "[FAIL] a market Buy on an untraded, empty book answered ${C} ${R:0:200} and ended $(status_of "$(ref)") (FR-LOB04: PRICE_MISSING; the seed tick is a mark, not a last trade)"; fi

step "2. FR-LOB04: validated at the opposite best; fills the depth, cancels the remainder"
order "${ACCT_B}" Sell 5 "${PX}"; [[ "${C}" == 200 && "${R}" != *'"kind":2'* ]] || fail "the resting Sell was not accepted"
SELL="$(ref)"
T0="$(trades_all)"
order "${ACCT_A}" Buy 8; [[ "${C}" == 200 && "${R}" != *'"kind":2'* ]] || fail "the market Buy against a resting Sell was refused (FR-LOB04: validated at the opposite best)"
MKT="$(ref)"
settle 2 || fail "SQL trades on ${T}: $(sides), want 2"
px="$(sql "SELECT GROUP_CONCAT(DISTINCT price) FROM trades WHERE security='${T}'")"; [[ "${px%%.*}" == "${PX}" ]] || fail "filled at ${px}, want ${PX} (the resting price)"
q="$(sql "SELECT GROUP_CONCAT(DISTINCT quantity) FROM trades WHERE security='${T}'")"; [[ "${q}" == 5 ]] || fail "trade quantities ${q}, want 5 (the depth)"
for i in $(seq 1 20); do [[ "$(pos "${ACCT_A}")" == 5 ]] && break; sleep 1; done
[[ "$(pos "${ACCT_A}")" == 5 && "$(pos "${ACCT_B}")" == -5 ]] || fail "positions A=$(pos "${ACCT_A}") B=$(pos "${ACCT_B}"), want +5/-5"
for i in $(seq 1 20); do s="$(status_of "${MKT}")"; [[ -n "${s}" && "${s}" != NEW && "${s}" != PARTIALLY_FILLED ]] && break; sleep 1; done
[[ "${s}" != NEW && "${s}" != PARTIALLY_FILLED && -n "${s}" ]] || fail "the market order ${MKT} is ${s:-absent} in SQL: its remainder was not cancelled in place"
ok "5 of 8 filled @${px}; positions +5/-5; market order ${MKT} ends ${s}, Sell ${SELL} $(status_of "${SELL}")"

step "3. control: a limit Sell at the same price now RESTS (the market remainder is gone)"
T0="$(trades_all)"
order "${ACCT_B}" Sell 3 "${PX}"; [[ "${C}" == 200 && "${R}" != *'"kind":2'* ]] || fail "the control Sell was refused"
same_trades "${T0}" || fail "the control Sell traded (${T0} -> $(trades_all)): a market remainder rested"
CTL="$(ref)"; for i in $(seq 1 20); do [[ "$(status_of "${CTL}")" == NEW ]] && break; sleep 1; done
[[ "$(status_of "${CTL}")" == NEW ]] || fail "the control Sell is $(status_of "${CTL}"), want NEW (resting)"
ok "Sell ${CTL} rests; no trade"

step "4. FR-LOB05: a price tick through a resting Buy fills nothing"
order "${ACCT_A}" Buy 2 48; [[ "${C}" == 200 && "${R}" != *'"kind":2'* ]] || fail "the resting Buy @48 was refused"
BUY="$(ref)"; T0="$(trades_all)"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-market-order' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":45}")"
[[ "${c}" == 200 ]] || fail "re-seed @45: ${c} $(body)"
same_trades "${T0}" || fail "the tick through the Buy's price traded: members ${T0} -> $(trades_all) (FR-LOB05)"
[[ "$(sides)" == 2 ]] || fail "SQL trades on ${T}: $(sides), want still 2"
for i in $(seq 1 10); do [[ "$(status_of "${BUY}")" == NEW ]] && break; sleep 1; done
[[ "$(status_of "${BUY}")" == NEW ]] || fail "the Buy @48 is $(status_of "${BUY}") after the tick, want NEW"
ok "tick @45 through Buy ${BUY} @48: no trade, Buy still NEW"

step "5. cleanup: cancel what rests"
for r in "${CTL}" "${BUY}"; do curl -s -m 10 -o /dev/null -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${r}}"; done
[[ -z "${SUSPECT:-}" ]] || fail "step 1 failed (above); steps 2-4 passed"
echo; echo "YU18-MARKET-ORDER PASS ticker=${T}"
