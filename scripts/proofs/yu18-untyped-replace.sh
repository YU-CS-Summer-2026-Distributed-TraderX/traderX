#!/usr/bin/env bash
# yu18-untyped-replace.sh — YU18 FR-ERH-F6: an untyped replace (no orderType) of a stop or an iceberg is refused
# and changes nothing; before ENGINE-REVIEW F6 it "replaced" their fields with zeros.
#
#   1. one cross at PX (a last trade for the stop to sit against), then a resting ICEBERG Sell 30 (tranche 10)
#      and a pending STOP Buy 4 (stop PX+3)
#   2. each replaced WITHOUT orderType: refused; at the effect end the order read model's row is unchanged
#      (type, stop price, tranche, remaining, limit, status; GET /accounts/{id}/orders, which production's snapshot read
#      model serves without an SQL orderbook) and every member's book still shows the iceberg's tranche
#   3. control: a TYPED replace of the iceberg (tranche 10 -> 5) is accepted and its row changes, so step 2's
#      "unchanged" is a refusal, not a replace path that does nothing
#
# Negative control: NEGATIVE=1 sends step 2 typed. That must FAIL (the iceberg row changes); a pass means step 2
# cannot see a replace that took effect.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [NEGATIVE=1] bash scripts/proofs/yu18-untyped-replace.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
# row <acct> <ref> — the read model's row: "type status remaining limit stop tranche tif"
row() { curl -s -m 10 -H "Authorization: Bearer ${ADMIN}" "${TP}/accounts/$1/orders?status=all" | jget \
  "' '.join(str(x.get(k)) for x in d if x['id'].endswith('-$2') for k in ('orderType','status','remainingQuantity','limitPrice','stopPrice','displayQuantity','timeInForce'))"; }
wait_row() { local i r; for i in $(seq 1 30); do r="$(row "$1" "$2")"; [[ -n "${r}" && "${r}" == *"$3"* ]] && { echo "${r}"; return 0; }; sleep 1; done; echo "${r}"; return 1; }
order() { # order <json fields> -> orderRef; fails unless accepted
  local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"ticker\":\"${T}\",$1}")"
  [[ "${c}" == 200 && "$(body)" != *REJECTED* ]] || fail "order {$1} not accepted: ${c} $(body)"
  body | jget 'd.get("orderRef")'
}
replace() { code -X POST "${GW}/replace" "${J[@]}" -d "$1"; }
# tranche — every member's visible ask quantity on the ticker (the iceberg shows only its tranche), "m0 m1 m2"
tranche() { local m v=""; for ((m=0; m<MEMBERS; m++)); do v+="$(mget "${m}" "/book?ticker=${T}" | jget "sum(l['qty'] for l in d.get('asks', []))") "; done; echo "${v% }"; }

step "0. seed ${T} @${PX}; one cross so the stop has a last trade"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-untyped-replace' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
order "\"accountId\":${ACCT_B},\"side\":\"Sell\",\"quantity\":1,\"limitPrice\":${PX}" >/dev/null
order "\"accountId\":${ACCT_A},\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PX}" >/dev/null
ok "seeded and crossed 1 @${PX}"

step "1. a resting ICEBERG and a pending STOP"
ICE="$(order "\"accountId\":${ACCT_B},\"side\":\"Sell\",\"quantity\":30,\"orderType\":\"ICEBERG\",\"timeInForce\":\"GTC\",\"limitPrice\":$((PX + 2)),\"displayQuantity\":10")"
STP="$(order "\"accountId\":${ACCT_A},\"side\":\"Buy\",\"quantity\":4,\"orderType\":\"STOP\",\"timeInForce\":\"GTC\",\"stopPrice\":$((PX + 3))")"
I0="$(wait_row "${ACCT_B}" "${ICE}" NEW)" || fail "iceberg ${ICE} never rested: ${I0}"
S0="$(wait_row "${ACCT_A}" "${STP}" PENDING_TRIGGER)" || fail "stop ${STP} never pending: ${S0}"
B0="$(tranche)"; [[ "${B0}" == "10 10 10" ]] || fail "members' visible ask ${B0}, want the 10-lot tranche on each"
ok "iceberg ${ICE}: ${I0} (members' ask size ${B0}); stop ${STP}: ${S0}"

step "2. untyped replaces are refused and change nothing${NEGATIVE:+ (NEGATIVE: sent TYPED)}"
if [[ "${NEGATIVE:-0}" == 1 ]]; then
  ri="{\"orderRef\":${ICE},\"side\":\"Sell\",\"quantity\":25,\"limitPrice\":$((PX + 2)),\"orderType\":\"ICEBERG\",\"timeInForce\":\"GTC\",\"displayQuantity\":5}"
else
  ri="{\"orderRef\":${ICE},\"side\":\"Sell\",\"quantity\":25,\"limitPrice\":$((PX + 2))}"
fi
rs="{\"orderRef\":${STP},\"side\":\"Buy\",\"quantity\":6,\"limitPrice\":$((PX + 4))}"
c1="$(replace "${ri}")"; b1="$(body)"; c2="$(replace "${rs}")"; b2="$(body)"
echo "    iceberg replace -> ${c1} ${b1:0:160}"; echo "    stop replace    -> ${c2} ${b2:0:160}"
sleep 3   # a replace that took effect reaches SQL within the read model's lag; this outwaits it before comparing
I1="$(row "${ACCT_B}" "${ICE}")"; S1="$(row "${ACCT_A}" "${STP}")"; B1="$(tranche)"
[[ "${I1}" == "${I0}" ]] || fail "the untyped replace changed the iceberg: ${I0} -> ${I1} (FR-ERH-F6)"
[[ "${S1}" == "${S0}" ]] || fail "the untyped replace changed the stop: ${S0} -> ${S1} (FR-ERH-F6)"
[[ "${B1}" == "${B0}" ]] || fail "members' visible ask ${B0} -> ${B1}: the iceberg's tranche moved (FR-ERH-F6)"
[[ "${b1}" != *'"status":"ACCEPTED"'* && "${b2}" != *'"status":"ACCEPTED"'* ]] || fail "a replace answered ACCEPTED"
ok "both refused; rows and the members' book unchanged"

step "3. control: a TYPED replace of the iceberg takes effect"
c="$(replace "{\"orderRef\":${ICE},\"side\":\"Sell\",\"quantity\":25,\"limitPrice\":$((PX + 2)),\"orderType\":\"ICEBERG\",\"timeInForce\":\"GTC\",\"displayQuantity\":5}")"
I2="$(wait_row "${ACCT_B}" "${ICE}" " 25 ")" || fail "the typed replace did not reach SQL (${c} $(body)): ${I2}; step 2's 'unchanged' then proves nothing"
[[ "$(awk '{print $6}' <<<"${I2}")" == 5* ]] || fail "typed replace row ${I2}: tranche not 5"
for i in $(seq 1 10); do B2="$(tranche)"; [[ "${B2}" == "5 5 5" ]] && break; sleep 1; done
[[ "${B2}" == "5 5 5" ]] || fail "members' visible ask ${B2} after the typed replace, want 5 on each"
ok "typed replace ${c}: ${I0} -> ${I2}; members' visible ask ${B0} -> ${B2}"

step "4. clean up"
for r in "${ICE}" "${STP}"; do code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${r}}" >/dev/null; done
echo; echo "YU18-UNTYPED-REPLACE PASS ticker=${T}"
