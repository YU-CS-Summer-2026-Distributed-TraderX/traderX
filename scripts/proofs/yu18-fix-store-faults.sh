#!/usr/bin/env bash
# yu18-fix-store-faults.sh — ADR-086 under faults: lease expiry takeover with fencing, and a NATS outage.
# DESTRUCTIVE: SIGSTOPs one gateway JVM (through a `kubectl debug node` pod with the host's PID namespace;
# a signal from inside the container cannot stop PID 1) and scales NATS to 0. Side namespaces only.
# Needs 2+ cluster-gateway pods with FIX_SESSION_STORE=jetstream. The session half is the .mjs; this wrapper
# reads the effect end: the order queued on the stopped owner never reached the cluster, and the gateway
# that lost its store logged the ALERT.
#
# Usage: CTX=<ctx> NS=<ns> DESTRUCTIVE=1 [ACCT_B=42422] bash scripts/proofs/yu18-fix-store-faults.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${DESTRUCTIVE:-0}" == 1 ]] || fail "DESTRUCTIVE=1 required: this stops a gateway and NATS"
[[ "${NS}" != traderx ]] || fail "never against production"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50

step "0. two replicas with persistent FIX sessions"
read -r -a PODS <<<"$("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name | sort | tr "\n" " ")"
(( ${#PODS[@]} >= 2 )) || fail "need 2+ running cluster-gateway pods, found ${#PODS[@]}"
for p in "${PODS[0]}" "${PODS[1]}"; do
  [[ "$("${K[@]}" get "${p}" -o jsonpath='{.spec.containers[0].env[?(@.name=="FIX_SESSION_STORE")].value}')" == jetstream ]] \
    || fail "${p} has no FIX_SESSION_STORE=jetstream"
done
NODE_A="$("${K[@]}" get "${PODS[0]}" -o jsonpath='{.spec.nodeName}')"
ok "A ${PODS[0]#pod/} on ${NODE_A}; B ${PODS[1]#pod/}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf FA "${PODS[0]}" 18130 tcp
pf FB "${PODS[1]}" 18130 tcp
PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "auth-secrets has no fix-session-password"
# signal <SIG>: from a debug pod in the node's PID namespace; waits for it to finish.
signal() {
  local out pod
  out="$("${K[@]}" debug "node/${NODE_A}" --image=busybox:1.36 --profile=sysadmin -- pkill "-$1" -f ClusterGatewayMain 2>&1)"
  pod="$(grep -o 'node-debugger-[a-z0-9-]*' <<<"${out}" | head -1)"
  [[ -n "${pod}" ]] || { echo "${out}"; return 1; }
  "${K[@]}" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/${pod}" --timeout=60s >/dev/null
}
SIGNAL_LIB="$(declare -p K NODE_A); $(declare -f signal)"
cont_on_exit() { signal CONT >/dev/null 2>&1 || true; _lib_yu18_cleanup; }
trap cont_on_exit EXIT   # never leave a gateway stopped
orders() { curl -s -m 10 "${TP}/accounts/${ACCT_B}/orders?status=all" \
  | python3 -c "import sys,json;print(sum(1 for o in json.load(sys.stdin) if o.get('security')=='${T}'))"; }
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091

step "1. seed ${T}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-store-faults' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
ok "seeded"

step "2-3. the session through both faults"
FIX_PORT_A="${FA}" FIX_PORT_B="${FB}" FIX_PASSWORD="${PW}" TICKER="${T}" PX="${PX}" ACCT_B="${ACCT_B}" \
  STOP_A="${SIGNAL_LIB}; signal STOP" CONT_A="${SIGNAL_LIB}; signal CONT" \
  NATS_DOWN="${K[*]} scale deploy/nats --replicas=0 >/dev/null && ${K[*]} wait --for=delete pod -l app=nats --timeout=120s >/dev/null" \
  NATS_UP="${K[*]} scale deploy/nats --replicas=1 >/dev/null && ${K[*]} rollout status deploy/nats --timeout=180s >/dev/null" \
  node "${here}/yu18-fix-store-faults.mjs" || fail "the FIX session failed (above)"

step "4. effect end"
for i in $(seq 1 60); do n="$(orders 2>/dev/null)"; [[ "${n}" == 3 ]] && break; sleep 2; done
[[ "${n}" == 3 ]] || fail "read model holds ${n:-?} orders on ${T}, want 3 (s1 s2 s3): the order queued on the stopped owner must not exist"
ok "3 orders on ${T}: the stale order never reached the cluster"
LOG_B="$("${K[@]}" logs "${PODS[1]}")"   # whole log first: grep -q under pipefail SIGPIPEs kubectl
ALERT="$(grep 'ALERT FIX session store unavailable (NATS' <<<"${LOG_B}" | head -1)"
[[ -n "${ALERT}" ]] || fail "B never logged the store ALERT"
ok "B logged: ${ALERT:0:170}"
LOG_A="$("${K[@]}" logs "${PODS[0]}")"
FENCE="$(grep 'lease not confirmed' <<<"${LOG_A}" | head -1)"
grep -q 'lease lost\|lease not confirmed' <<<"${LOG_A}" || fail "A never logged losing the lease after SIGCONT"
ok "A logged: ${FENCE:0:170}"
echo; echo "YU18-FIX-STORE-FAULTS PASS ticker=${T}"
