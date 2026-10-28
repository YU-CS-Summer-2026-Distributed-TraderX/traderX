#!/usr/bin/env bash
# yu18-fix-resume.sh — a FIX session resumes on a DIFFERENT gateway replica (ADR-086; YU10 FR-FIX04,
# FR-FIX11, FR-FIX12(d)). Needs 2+ cluster-gateway pods with FIX_SESSION_STORE=jetstream. The session
# half is yu18-fix-resume.mjs, which forwards to two named pods (not the Service, whose affinity would
# hide the replica change); this wrapper picks the pods and reads the effect end from the members and SQL:
#
#   - the members booked exactly two crosses (four sides) on the fresh ticker: B1 x S1 over FIX, and a
#     REST Buy x S3 while the session was live nowhere
#   - the order read model holds the five orders: four FILLED, S2 CANCELED (cancelled on B by an
#     OrigClOrdID it learned from A's stored reports)
#
# Negative control (NEGATIVE=1, gateways WITHOUT FIX_SESSION_STORE): passes only if the session fails
# where TD-AC01 says it must: B answers the resumed Logon at MsgSeqNum 1 (suspect 9).
#
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [NEGATIVE=1] bash scripts/proofs/yu18-fix-resume.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50

step "0. two gateway replicas, each with persistent FIX sessions"
read -r -a PODS <<<"$("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name | sort | tr "\n" " ")"
(( ${#PODS[@]} >= 2 )) || fail "need 2+ running cluster-gateway pods, found ${#PODS[@]} (scale the Deployment first)"
for p in "${PODS[0]}" "${PODS[1]}"; do
  store="$("${K[@]}" get "${p}" -o jsonpath='{.spec.containers[0].env[?(@.name=="FIX_SESSION_STORE")].value}')"
  if [[ "${NEGATIVE:-0}" == 1 ]]; then
    [[ -z "${store}" ]] || fail "NEGATIVE=1 needs gateways without FIX_SESSION_STORE; ${p} has '${store}'"
  else
    [[ "${store}" == jetstream ]] || fail "${p} has FIX_SESSION_STORE='${store}' (ADR-086 needs jetstream); NEGATIVE=1 runs the control"
  fi
done
[[ "${PODS[0]}" != "${PODS[1]}" ]] || fail "both forwards would reach one pod"
ok "replica A ${PODS[0]#pod/}, replica B ${PODS[1]#pod/}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf FA "${PODS[0]}" 18130 tcp
pf FB "${PODS[1]}" 18130 tcp
PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "auth-secrets has no fix-session-password"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }

step "1. seed ${T} @${PX}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-resume' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
T0="$(trades_all)"
ok "seeded; members' trades ${T0}"

step "2-7. the session, across two replicas"
GW="${GW}" BEARER="${ADMIN}" FIX_PORT_A="${FA##*:}" FIX_PORT_B="${FB##*:}" FIX_PASSWORD="${PW}" TICKER="${T}" PX="${PX}" ACCT_A="${ACCT_A}" ACCT_B="${ACCT_B}" \
  node "${here}/yu18-fix-resume.mjs" | tee "${BODY_FILE}.fix"; rc=${PIPESTATUS[0]}
if [[ "${NEGATIVE:-0}" == 1 ]]; then
  grep -q "B's Logon is MsgSeqNum 1;" "${BODY_FILE}.fix" && [[ "${rc}" != 0 ]] \
    || fail "NEGATIVE: without the store the session should restart at MsgSeqNum 1 on B (suspect 9); it did not"
  echo; echo "YU18-FIX-RESUME NEGATIVE PASS: without the store B restarts the session at 1"; exit 0
fi
[[ "${rc}" == 0 ]] || fail "the FIX session failed (above)"

step "8. effect end: the members and SQL agree with the session"
for i in $(seq 1 30); do T1="$(trades_all)"; [[ "${T1}" != "${T0}" ]] && break; sleep 1; done
read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"
for i in $(seq 1 30); do T1="$(trades_all)"; read -r -a b <<<"${T1}"; [[ $(( b[0] - a[0] )) -ge 4 ]] && break; sleep 1; done
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == 4 ]] || fail "member ${m} trades ${a[m]} -> ${b[m]} (want +4: two crosses)"; done
# The order read model the gateway's status answers come from (GET /accounts/{id}/orders, ADR-080): on
# this profile the order feed is not persisted, so SQL orderbook is not where orders live.
[[ -n "${TP:-}" ]] || pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
st() { for acct in "${ACCT_A}" "${ACCT_B}"; do curl -s -m 10 "${TP}/accounts/${acct}/orders?status=all"; echo; done \
  | python3 -c "import sys,json;print(' '.join(sorted(o['status'] for l in sys.stdin if l.strip() for o in json.loads(l) if o.get('security')=='${T}')))"; }
for i in $(seq 1 30); do [[ "$(st)" == *CANCELED* ]] && break; sleep 1; done
rows="$(st)"
[[ "${rows}" == "CANCELED FILLED FILLED FILLED FILLED" ]] \
  || fail "read model on ${T}: '${rows}', want the five orders (S1 B1 S2 S3, the REST Buy): 4 FILLED, S2 CANCELED"
ok "members ${T0} -> ${T1}; read model ${rows}"

echo; echo "YU18-FIX-RESUME PASS ticker=${T}"
