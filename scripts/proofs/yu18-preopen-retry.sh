#!/usr/bin/env bash
# yu18-preopen-retry.sh — YU18 FR-ERH-F9: a PRE_OPEN retry of a key the engine already decided is not queued.
# Before ENGINE-REVIEW F9 the PRE_OPEN gate queued it, and the open executed the key a second time.
#
#   1. OPEN: a Buy under clientOrderId K rests (decided: accepted, orderRef r)
#   2. PRE_OPEN; K resent: answered with r, and every member's queueDepth stays 0
#   3. OPEN: the read model holds exactly ONE order on the ticker (r), every member's book one resting buy;
#      nothing executed twice
# The session is put back OPEN on every exit path.
#
# Negative control: NEGATIVE=1 resends under a fresh key in step 2. That is a new order: it is queued
# (queueDepth 1) and released at the open, so step 2 and 3 must FAIL; a pass means they cannot see a queued retry.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214] [NEGATIVE=1] bash scripts/proofs/yu18-preopen-retry.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; T="$(mint_ticker)"; PX=50; K1="pa-f9-$$-$(date +%s)"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
OP=(-H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-preopen-retry')
phase() { local c; c="$(code -X POST "${GW}/session" "${OP[@]}" -d "{\"phase\":\"$1\"}")"; [[ "${c}" == 200 ]] || fail "phase $1: ${c} $(body)"
  local i; for i in $(seq 1 20); do [[ "$(mget 0 /health | jget 'd.get("phase")')" == "$1" ]] && return 0; sleep 0.5; done; fail "member 0 never reached $1"; }
trap 'code -X POST "${GW}/session" "${OP[@]}" -d "{\"phase\":\"OPEN\"}" >/dev/null; _lib_yu18_cleanup' EXIT
order() { code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":${ACCT_A},\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":$((PX - 1)),\"clientOrderId\":\"$1\"}"; }
depths() { local m v=""; for ((m=0; m<MEMBERS; m++)); do v+="$(mget "${m}" /health | jget 'd.get("queueDepth")') "; done; echo "${v% }"; }
bids() { local m v=""; for ((m=0; m<MEMBERS; m++)); do v+="$(mget "${m}" "/book?ticker=${T}" | jget "sum(l['qty'] for l in d.get('bids', []))") "; done; echo "${v% }"; }
orders() { curl -s -m 10 -H "Authorization: Bearer ${ADMIN}" "${TP}/accounts/${ACCT_A}/orders?status=all" | jget "' '.join(sorted(x['id'] for x in d if x['security']=='${T}'))"; }

step "0. seed ${T} @${PX}; the session OPEN"
c="$(code -X POST "${GW}/seed" "${OP[@]}" -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"; [[ "${c}" == 200 ]] || fail "seed: ${c} $(body)"
phase OPEN; [[ "$(depths)" == "0 0 0" ]] || fail "queueDepth $(depths) before the proof"
ok "seeded; OPEN; queueDepth 0 on every member"

step "1. OPEN: K rests"
c="$(order "${K1}")"; R="$(body | jget 'd.get("orderRef")')"
[[ "${c}" == 200 && -n "${R}" && "$(body)" != *REJECTED* ]] || fail "K not accepted: ${c} $(body)"
for i in $(seq 1 20); do [[ "$(orders)" == *"-${R}" ]] && break; sleep 0.5; done
ok "K -> orderRef ${R}; read model: $(orders); members' bids $(bids)"

step "2. PRE_OPEN: K resent${NEGATIVE:+ (NEGATIVE: under a FRESH key)}"
phase PRE_OPEN
K2="${K1}"; [[ "${NEGATIVE:-0}" == 1 ]] && K2="${K1}-x"
c="$(order "${K2}")"; b="$(body)"; echo "    resend -> ${c} ${b:0:200}"
sleep 1; d="$(depths)"
[[ "${d}" == "0 0 0" ]] || fail "queueDepth ${d} after the PRE_OPEN resend: the decided key was queued (FR-ERH-F9)"
[[ "$(jget 'd.get("orderRef")' <<<"${b}")" == "${R}" ]] || fail "the resend was not answered with the original orderRef ${R}: ${b:0:200}"
ok "answered with ${R}; queueDepth ${d}"

step "3. OPEN: nothing executed twice"
phase OPEN; sleep 2
for i in $(seq 1 20); do o="$(orders)"; [[ -n "${o}" ]] && break; sleep 0.5; done
[[ "${o}" == "1-${R}" ]] || fail "read model holds '${o}' on ${T}, want only 1-${R} (FR-ERH-F9: the open released a second order)"
[[ "$(bids)" == "1 1 1" ]] || fail "members' bids $(bids) on ${T}, want one 1-lot on each"
ok "one order (1-${R}); members' bids $(bids)"
code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${R}}" >/dev/null

echo; echo "YU18-PREOPEN-RETRY PASS ticker=${T}"
