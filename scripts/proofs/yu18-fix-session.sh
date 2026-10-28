#!/usr/bin/env bash
# yu18-fix-session.sh — FIX session behaviour on the GKE gateway, asserted at the effect end.
# YU10 FR-FIX04/10/11/12 and YU18 FR-ERH-S3 (a961d89a: Logon needs CLIENT1's Password(554), from
# auth-secrets/fix-session-password). The session half is yu18-fix-session.mjs; this wrapper seeds a
# fresh ticker and reads what the session claims back from the members and SQL:
#
#   - three refused logons (no 554, a wrong 554, a CompID outside FIX_SESSION_AUTH) beside the
#     accepted CLIENT1 logon — the negative controls of FR-ERH-S3
#   - after a resting Sell, its ClOrdID resent (FR-FIX10), a crossing Buy, an unknown-symbol order
#     (FR-FIX12 c) and a D without Side (FR-FIX12 b): SQL `orderbook` holds exactly TWO orders on the
#     ticker, every member booked exactly two trade sides, and SQL positions are +5 / -5
#   - FR-FIX04 status and FR-FIX11 reconnect/resend are asserted inside the session (the .mjs)
#
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] bash scripts/proofs/yu18-fix-session.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf FIXP "${FIX_TARGET:-svc/order-matcher-gw-fix}" 18130 tcp  # one pod for the life of the forward; yu18-fix-resume crosses replicas
PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "auth-secrets has no fix-session-password (FR-ERH-S3: GKE sources it there)"
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
orders() { sql "SELECT COUNT(*) FROM orderbook WHERE security='${T}'"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }

step "0. seed ${T} @${PX}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-session' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
T0="$(trades_all)"; [[ "$(orders)" == 0 ]] || fail "${T} already has orders"
ok "seeded; members' trades ${T0}"

step "1-6. the FIX session"
OUT="$(FIX_HOST=127.0.0.1 FIX_PORT="${FIXP}" FIX_PASSWORD="${PW}" TICKER="${T}" PX="${PX}" ACCT_A="${ACCT_A}" ACCT_B="${ACCT_B}" \
  node "${here}/yu18-fix-session.mjs" 2>&1)"; rc=$?
printf '%s\n' "${OUT}" | grep -v '^RESULT'
# The effect end is read whatever the session reported: a missing report and a missing booking are
# different findings.
[[ "${OUT}" == *'RESULT logon_ok=1'* ]] || fail "the session never logged on (above)"

step "7. effect end: the members and SQL agree with the session"
for i in $(seq 1 30); do T1="$(trades_all)"; [[ "${T1}" != "${T0}" ]] && break; sleep 1; done
read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == 2 ]] || fail "member ${m} trades ${a[m]} -> ${b[m]} (want +2: one cross; the resent ClOrdID and the rejects book nothing)"; done
for i in $(seq 1 30); do [[ "$(orders)" -ge 2 && "$(pos "${ACCT_A}")" == 5 ]] && break; sleep 1; done
n="$(orders)"; [[ "${n}" == 2 ]] || fail "SQL orderbook holds ${n} orders on ${T}, want 2 (the resent ClOrdID must not add one: FR-FIX10)"
[[ "$(pos "${ACCT_A}")" == 5 && "$(pos "${ACCT_B}")" == -5 ]] || fail "positions ${ACCT_A}=$(pos "${ACCT_A}") ${ACCT_B}=$(pos "${ACCT_B}"), want +5/-5"
ok "members ${T0} -> ${T1}; 2 orders on ${T}; positions +5/-5; resend answered: $(sed -n 's/^RESULT dup_answer=//p' <<<"${OUT}")"

step "8. no FIX password in any gateway log (the refused logons above carried it; ADR-082 S3 redaction)"
for p in $("${K[@]}" get pods -l app=cluster-gateway -o name); do
  l="$("${K[@]}" logs "${p}" 2>/dev/null)"
  ! grep -qF "${PW}" <<<"${l}" || fail "${p#pod/} logs the FIX session password in clear ($(grep -cF "${PW}" <<<"${l}") lines)"
done
ok "the password is in no gateway log ($(grep -c NOTBOUND <<<"${l}") NOTBOUND line(s) in the last pod, masked)"

[[ "${rc}" == 0 ]] || fail "the effect end holds, but the FIX session failed the checks above"
echo; echo "YU18-FIX-SESSION PASS ticker=${T}"
