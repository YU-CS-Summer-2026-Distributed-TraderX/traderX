#!/usr/bin/env bash
# yu18-fix-duplicate.sh — YU18 FR-XOC-03 (ADR-091 §3): a FIX resend whose original has left the engine's
# terminal retention is answered as an Order Status report for the original, and books nothing.
# The session is yu18-fix-duplicate.mjs; this wrapper seeds two fresh tickers and reads the effect end in the
# order read model (GET /accounts/{id}/orders?status=all; production's snapshot read model writes no SQL orderbook):
# the ticker holds exactly S1 (FILLED, ACCT_B) and B1 (ACCT_A), the filler ticker exactly FILLER CANCELED IOCs,
# SQL positions +5/-5, and every member booked exactly one cross (two sides).
#
# Negative control: NEGATIVE=1 resends under a fresh ClOrdID. That must FAIL (the answer is a new order,
# and the read model holds a second ACCT_B order); a pass under NEGATIVE=1 means the proof cannot tell a duplicate from a new key.
#
# Needs: a gateway with FIX_SESSION_AUTH binding CLIENT1 to auth-secrets/fix-session-password, ORDER_READMODEL_URL
# set (39 comes from the read model), and no other writer trading ACCT_A/ACCT_B on PA* tickers.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [FILLER=1100] [NEGATIVE=1] bash scripts/proofs/yu18-fix-duplicate.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; FT="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf FIXP "${FIX_TARGET:-svc/order-matcher-gw-fix}" 18130 tcp
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "auth-secrets has no fix-session-password"
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
# orders <acct> <ticker> [status] — the read model's orders of one account on one ticker, as "<n> <statuses>"
orders() { curl -s -m 10 -H "Authorization: Bearer ${ADMIN}" "${TP}/accounts/$1/orders?status=all" | jget \
  "(lambda r: f'{len(r)} {\",\".join(sorted(set(x[\"status\"] for x in r)))}')([x for x in d if x['security']=='$2'])"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }

step "0. seed ${T} and the filler ticker ${FT} @${PX}"
for t in "${T}" "${FT}"; do
  c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-duplicate' \
    -d "{\"accountId\":${ACCT_A},\"tickers\":\"${t}\",\"price\":${PX}}")"
  [[ "${c}" == 200 ]] || fail "operator /seed ${t}: ${c} $(body)"
done
T0="$(trades_all)"; [[ "$(orders "${ACCT_B}" "${T}")" == "0 " ]] || fail "${T} already has orders"
ok "seeded; members' trades ${T0}"

step "1-4. the FIX session"
OUT="$(FIX_HOST=127.0.0.1 FIX_PORT="${FIXP}" FIX_PASSWORD="${PW}" TICKER="${T}" FILLER_TICKER="${FT}" PX="${PX}" \
  ACCT_A="${ACCT_A}" ACCT_B="${ACCT_B}" FILLER="${FILLER:-1100}" NEGATIVE="${NEGATIVE:-0}" node "${here}/yu18-fix-duplicate.mjs" 2>&1)"; rc=$?
printf '%s\n' "${OUT}" | grep -v '^RESULT'
[[ "${OUT}" == *'RESULT logon_ok=1'* ]] || fail "the session never logged on (above)"

step "5. effect end: the resend booked nothing"
F="${FILLER:-1100}"
for i in $(seq 1 30); do [[ "$(orders "${ACCT_A}" "${FT}")" == "${F} CANCELED" && "$(pos "${ACCT_A}")" == 5 ]] && break; sleep 1; done
f="$(orders "${ACCT_A}" "${FT}")"; [[ "${f}" == "${F} CANCELED" ]] || fail "filler ${FT}: read model holds '${f}', want '${F} CANCELED' (terminal, so S1 left retention)"
T1="$(trades_all)"; read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == 2 ]] || fail "member ${m} trades ${a[m]} -> ${b[m]} (want +2: one cross; the IOCs and the resend book nothing)"; done
nb="$(orders "${ACCT_B}" "${T}")"; [[ "${nb}" == "1 FILLED" ]] || fail "read model: ${ACCT_B} on ${T} holds '${nb}', want '1 FILLED': the resend booked an order or changed S1 (FR-XOC-01/02)"
na="$(orders "${ACCT_A}" "${T}")"; [[ "${na}" == "1 FILLED" ]] || fail "read model: ${ACCT_A} on ${T} holds '${na}', want '1 FILLED'"
[[ "$(pos "${ACCT_A}")" == 5 && "$(pos "${ACCT_B}")" == -5 ]] || fail "positions ${ACCT_A}=$(pos "${ACCT_A}") ${ACCT_B}=$(pos "${ACCT_B}"), want +5/-5"
ok "members ${T0} -> ${T1}; filler ${f}; ${T}: S1 and B1 FILLED, nothing else; positions +5/-5"

[[ "${rc}" == 0 ]] || fail "the effect end holds, but the FIX session failed the checks above"
echo; echo "YU18-FIX-DUPLICATE PASS ticker=${T}"
