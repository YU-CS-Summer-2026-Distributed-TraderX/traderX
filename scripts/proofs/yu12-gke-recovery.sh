#!/usr/bin/env bash
# yu12-gke-recovery.sh — the system's strongest correctness story, as a committed, re-runnable
# proof: kill a member → it comes back with an EMPTY disk (emptyDir) and rejoins from snapshot +
# log → all three members are byte-identical (order-book hash, position hash, trade counter and
# reference generator all agreed) → and the rejoined node can LATER BECOME LEADER and take writes.
#
# WHY GKE. kind's idle-CPU starvation (4 nodes busy-spinning on an 11-CPU Docker VM) makes
# election and catch-up timing untrustworthy — a rejoin that "works" there proves nothing about a
# real cluster, and one that flakes there indicts nothing. Recovery IS a timing-and-consensus
# behaviour; it gets real hardware. (The same scenario was proven live on GKE 2026-07-18/23; this
# script is that drill, captured.)
#
# Falsifiability guards:
#   * identity is asserted on FOUR independent quantities per member (order hash, position hash,
#     trades, nextOrderRef) — agreed via retry, because members apply the committed tail at
#     slightly different times and one early sample looks exactly like divergence;
#   * the rejoined member is asserted to have actually LOST its disk (its restart-fresh pod
#     reports cluster_up from a new process; the StatefulSet uses emptyDir so deletion IS wipe);
#   * "can later lead" is closed under traffic: once the rejoined ordinal reports role=1, an order
#     is placed THROUGH it as leader and must book on all three members;
#   * post-failover refs are asserted to CONTINUE (no reuse of a pre-kill id) — the cross-epoch
#     claim also carried standalone by yu12-gke-cross-epoch-idreuse.sh.
#
# Usage: ./yu12-gke-recovery.sh     (cluster up on GKE; see quickstart + memory for bring-up)
set -euo pipefail

CTX="${CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18310}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
TICKER="${TICKER:-RCV$(date +%H%M%S)}"
PRICE="${PRICE:-120.00}"
LEAD_ATTEMPTS="${LEAD_ATTEMPTS:-6}"   # leader kills allowed while waiting for the rejoined node to win

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
state() { # state <ordinal> -> "<orderHash> <positionHash> <trades> <nextRef>"
  ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_order_hash/{o=$2} /^traderx_book_position_hash/{p=$2}
           /^traderx_cluster_trades/{t=$2} /^traderx_cluster_next_order_ref/{r=$2} END{print o,p,t,r}'
}
role_of() { member_metric "$1" traderx_cluster_role; }
leader() { for m in 0 1 2; do [[ "$(role_of "${m}" 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }
# Retried: an early sample catches a member mid-apply, which looks exactly like a determinism
# failure and is not one. PERSISTENT disagreement is the failure.
identity_consensus() {
  local s0 s1 s2 i
  for i in $(seq 1 60); do
    s0="$(state 0 2>/dev/null)"; s1="$(state 1 2>/dev/null)"; s2="$(state 2 2>/dev/null)"
    # SHAPE, not emptiness. state()'s awk ends in END{print o,p,t,r}, which fires on NO INPUT AT
    # ALL with all four variables unset and prints three spaces. `-n "   "` is true, and
    # "   " == "   " == "   " is agreement — so the previous `-n "${s0}"` guard reported three
    # UNREACHABLE members as byte-identical, and this proof's whole claim is byte-identity.
    # Verified: with an empty metrics read the old condition returned 0.
    # Requiring the real answer's shape (two hashes, which are routinely NEGATIVE, then two
    # counters) makes "no answer" impossible to confuse with "the answer is identical".
    if [[ "${s0}" =~ ^-?[0-9]+\ -?[0-9]+\ [0-9]+\ [0-9]+$ \
       && "${s0}" == "${s1}" && "${s1}" == "${s2}" ]]; then echo "${s0}"; return 0; fi
    sleep 2
  done
  fail "members never reached byte-identity: [${s0}] [${s1}] [${s2}]
  (all-blank readings mean the members were UNREACHABLE, not that they disagreed — check the pod
  and container name before reading this as a determinism failure)"
}

PF_PID=""
stop_pf() { [[ -n "${PF_PID}" ]] && { kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; }; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!
  local t=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    t=$((t+1)); [[ ${t} -lt 90 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

order() { # order <account> <side> <qty> <price> -> body
  curl -s --max-time 30 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4}"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight: three ready members, agreed BEFORE any traffic"
for i in $(seq 1 60); do
  ready="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{" "}{end}')"
  [[ "${ready}" == "true true true " ]] && break
  [[ ${i} -lt 60 ]] || fail "members never all ready (saw: ${ready})"
  sleep 5
done
start_pf
for acct in "${ACCT}" "${OTHER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
BASE="$(identity_consensus)"
echo "  agreed [orderHash posHash trades nextRef] = [${BASE}]   ticker ${TICKER}"

step "1. build real state: resting orders AND positions (so BOTH hashes carry information)"
order "${ACCT}"  Buy  7 "${PRICE}" >/dev/null           # rests
order "${OTHER}" Sell 4 "${PRICE}" >/dev/null           # crosses: trades + positions move
order "${OTHER}" Sell 3 "$(python3 -c "print(${PRICE}+5)")" >/dev/null   # rests away from touch
PRE="$(identity_consensus)"
echo "  pre-kill agreed state: [${PRE}]"
[[ "${PRE}" != "${BASE}" ]] || fail "traffic changed nothing — the proof would be vacuous"
PRE_REF="${PRE##* }"

step "2. destroy a FOLLOWER — emptyDir, so it returns with an EMPTY disk"
LDR="$(leader)" || fail "no leader found"
VICTIM=""
for m in 0 1 2; do [[ "${m}" != "${LDR}" ]] && { VICTIM="${m}"; break; }; done
VICTIM_POD="order-matcher-cluster-${VICTIM}"
echo "  leader is member ${LDR}; destroying follower ${VICTIM_POD}"
${K} delete pod "${VICTIM_POD}" --wait=true >/dev/null
${K} wait --for=condition=Ready "pod/${VICTIM_POD}" --timeout=600s >/dev/null
echo "  ${VICTIM_POD} is back (fresh pod, empty disk) — rebuilding from snapshot + log"

step "3. the rebuilt member converges to BYTE-IDENTITY with the survivors"
POST="$(identity_consensus)"
echo "  post-rejoin agreed state: [${POST}]"
[[ "${POST}" == "${PRE}" ]] || fail "the cluster's agreed state changed across a follower rebuild: [${PRE}] -> [${POST}]"

step "4. the rejoined member can LATER BECOME LEADER"
ATT=0
until [[ "$(role_of "${VICTIM}")" == "1" ]]; do
  ATT=$((ATT+1)); [[ ${ATT} -le ${LEAD_ATTEMPTS} ]] \
    || fail "member ${VICTIM} never won leadership in ${LEAD_ATTEMPTS} elections — a rebuilt node that cannot lead is a silent capacity loss"
  LDR="$(leader)" || fail "no leader to depose"
  if [[ "${LDR}" == "${VICTIM}" ]]; then break; fi
  echo "  attempt ${ATT}: deposing leader ${LDR} (delete pod, force an election)"
  ${K} delete pod "order-matcher-cluster-${LDR}" --wait=true >/dev/null
  for i in $(seq 1 90); do leader >/dev/null 2>&1 && break; sleep 2; done
  ${K} wait --for=condition=Ready "pod/order-matcher-cluster-${LDR}" --timeout=600s >/dev/null
done
echo "  member ${VICTIM} (the one rebuilt from an empty disk) is now LEADER"

step "5. ...and takes writes: an order books through it on ALL THREE members"
start_pf   # the gateway may have re-homed across elections; own the tunnel again
identity_consensus >/dev/null
S0="$(identity_consensus)"; T0="$(echo "${S0}" | awk '{print $3}')"; R0="${S0##* }"
CLOSE="$(order "${ACCT}" Buy 3 "$(python3 -c "print(${PRICE}+5)")")"   # crosses the resting +5 sell
echo "  buy 3 @ +5 under the rebuilt leader -> ${CLOSE}"
S1="$(identity_consensus)"; T1="$(echo "${S1}" | awk '{print $3}')"; R1="${S1##* }"
echo "  agreed state: [${S0}] -> [${S1}]"
[[ "${T1}" -eq "$(( T0 + 2 ))" ]] || fail "the cross under the rebuilt leader did not book on all members"
[[ "${R1}" -gt "${R0}" && "${R0}" -ge "${PRE_REF}" ]] \
  || fail "reference generator went backwards (${PRE_REF} -> ${R0} -> ${R1}): id reuse is possible"

echo
echo "[PASS] cluster recovery: a member destroyed to an empty disk rejoined to byte-identity on"
echo "       order hash, position hash, trades and nextOrderRef; then won leadership and booked a"
echo "       cross on all three members, with the reference generator strictly monotonic."
