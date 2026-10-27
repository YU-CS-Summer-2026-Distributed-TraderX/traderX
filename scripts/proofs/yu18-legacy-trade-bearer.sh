#!/usr/bin/env bash
# yu18-legacy-trade-bearer.sh — FR-ERH-C2: the legacy UI's ticket (trade-service POST /trade/, the
# 001 baseline contract) still books after ADR-082 S1 put a bearer on the gateway's /trades, because
# trade-service mints its own short-lived one-account bearer; and a gateway refusal comes back as the
# gateway's 4xx with its reason, not as a 500.
#
#   1. control: the gateway's /trades with no bearer is 401 (so step 2 cannot pass by an open door)
#   2. POST /trade/ for account A books: every member's trade counter moves by exactly one and SQL
#      gains A's trade row on the security
#   3. a quantity-0 ticket is refused with a 4xx that carries the gateway's reason, and books nothing
#
# Usage: CTX=<ctx> NS=<ns> [ACCT=22214 SEC=IBM] bash scripts/proofs/yu18-legacy-trade-bearer.sh
#   SEC must already carry a mark (scripts/yu15/seed-proof-fixtures.sh seeds IBM).
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT="${ACCT:-22214}"; SEC="${SEC:-IBM}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TS "${TS_TARGET:-svc/trade-service}" 18092
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
rows() { sql "SELECT COUNT(*) FROM trades WHERE accountid=${ACCT} AND security='${SEC}'"; }
TICKET() { echo "{\"accountId\":${ACCT},\"security\":\"${SEC}\",\"side\":\"Buy\",\"quantity\":$1}"; }

step "1. control: the gateway's /trades refuses a bare caller"
c="$(code -X POST "${GW}/trades" -H 'Content-Type: application/json' -d "$(TICKET 7)")"
[[ "${c}" == 401 ]] || fail "bare gateway /trades answered ${c} (want 401): the door is open, step 2 would prove nothing"
ok "bare /trades: 401"

step "2. the legacy ticket books through trade-service"
T0="$(trades_all)"; R0="$(rows)"
c="$(code -X POST "${TS}/trade/" -H 'Content-Type: application/json' -d "$(TICKET 7)")"
[[ "${c}" == 2* ]] || fail "POST /trade/ answered ${c}: $(body | head -c 240)"
for i in $(seq 1 30); do T1="$(trades_all)"; [[ "${T1}" != "${T0}" ]] && break; sleep 1; done
read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == 1 ]] || fail "member ${m} trades ${a[m]} -> ${b[m]} (want +1)"; done
for i in $(seq 1 30); do [[ "$(rows)" -gt "${R0}" ]] && break; sleep 1; done
[[ "$(rows)" == $(( R0 + 1 )) ]] || fail "SQL trade rows for ${ACCT}/${SEC}: ${R0} -> $(rows) (want +1)"
ok "${c}; members ${T0} -> ${T1}; SQL rows ${R0} -> $(rows)"

step "3. a gateway refusal propagates as a 4xx with its reason"
T0="$(trades_all)"; R0="$(rows)"
c="$(code -X POST "${TS}/trade/" -H 'Content-Type: application/json' -d "$(TICKET 0)")"
echo "    quantity 0 -> ${c} $(body | head -c 200)"
[[ "${c}" == 4* ]] || fail "a refused ticket answered ${c}, not the gateway's 4xx (FR-ERH-C2)"
[[ "$(body)" =~ [A-Z_]{5,} ]] || fail "the 4xx carries no gateway reason: $(body | head -c 200)"
sleep 3
[[ "$(trades_all)" == "${T0}" && "$(rows)" == "${R0}" ]] || fail "the refused ticket booked something"
ok "${c} with reason ${BASH_REMATCH[0]}; nothing booked"

echo; echo "YU18-LEGACY-TRADE-BEARER PASS account=${ACCT} security=${SEC}"
