#!/usr/bin/env bash
# yu12-gke-cross-epoch-idreuse.sh — proves the reference generator NEVER reissues an order id from
# a prior epoch: across a real failover (leader killed, new leader elected), orderRefs continue
# STRICTLY ABOVE everything the old epoch issued — no overlap, no reset, agreed by all members.
#
# Why this is its own proof (and not just a line in the recovery/failover scripts): id reuse is
# the failure mode that silently corrupts every downstream identity — the ClOrdId ledger, the
# epoch-qualified read-model ids, trade-processor dedup ("Duplicate trade delivery ignored" ate
# real trades on 2026-07-22 for exactly this class of bug). The nextOrderRef-in-snapshot fix
# (moved YU11→YU12) is the change under test; this script is its standing regression proof.
#
# Method — assert at both ends of the failover, from the members' own counters:
#   1. issue orders in the OLD epoch; record every orderRef the gateway returned AND the members'
#      agreed next_order_ref high-water mark R_old;
#   2. kill the leader; wait for the new epoch (new leader, all members back);
#   3. issue orders in the NEW epoch; every returned orderRef must be >= R_old, the members'
#      agreed counter must be strictly monotonic, and the old and new ref SETS must be disjoint;
#   4. falsification arm: the new-epoch orders BOOK (trades move) — proving the refs are real
#      allocations, not a counter that wandered upward while allocation restarted from 1.
#
# WHY GKE: the scenario is an election; kind's starved CPUs make election behaviour meaningless.
# Usage: ./yu12-gke-cross-epoch-idreuse.sh   (GKE cluster up)
set -euo pipefail

CTX="${CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18330}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
TICKER="${TICKER:-EPO$(date +%H%M%S)}"
PRICE="${PRICE:-105.00}"
N_PER_EPOCH="${N_PER_EPOCH:-10}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
refs_all()   { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_next_order_ref 2>/dev/null)"; done; }
trades_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_trades 2>/dev/null)"; done; }
uniq_one()   { tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' '; }
agreed() { # agreed <fn> — retried; a mid-apply sample looks like divergence and is not
  local r i
  for i in $(seq 1 90); do
    r="$("$1")"
    [[ "$(echo "${r}" | uniq_one)" == "1" && -n "${r// /}" ]] && { echo "${r%% *}"; return 0; }
    sleep 2
  done
  fail "members never agreed on $1: [${r}]"
}
leader() { for m in 0 1 2; do [[ "$(member_metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }

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

order() { # order <account> <side> <qty> <price> -> body (retried until acked; failover-safe)
  local out t=0
  until out="$(curl -s --max-time 10 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
      -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4,\"clientOrderId\":\"epo-$$-${RANDOM}-${t}\"}" 2>/dev/null)" \
      && [[ "${out}" == *'"orderRef"'* ]]; do
    t=$((t+1)); [[ ${t} -lt 60 ]] || fail "order never acked"
    sleep 1
  done
  echo "${out}"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight: three ready members, gateway live, seeded"
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
LDR="$(leader)" || fail "no leader"
echo "[ok] leader is member ${LDR}; ticker ${TICKER}"

step "1. OLD epoch: issue ${N_PER_EPOCH} orders, record their refs and the high-water mark"
OLD_REFS=""
for i in $(seq 1 "${N_PER_EPOCH}"); do
  OLD_REFS+="$(ref_of "$(order "${ACCT}" Buy 1 "${PRICE}")") "
done
R_OLD="$(agreed refs_all)"
echo "  old-epoch refs: [${OLD_REFS}]"
echo "  members agree next_order_ref = ${R_OLD}"

step "2. kill the leader — force a new epoch"
${K} delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null
NEWLDR=""
for i in $(seq 1 120); do
  NEWLDR="$(leader 2>/dev/null || true)"
  [[ -n "${NEWLDR}" && "${NEWLDR}" != "${LDR}" ]] && break
  sleep 2
done
[[ -n "${NEWLDR}" && "${NEWLDR}" != "${LDR}" ]] || fail "no NEW leader emerged after the kill"
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${LDR}" --timeout=600s >/dev/null
echo "  new leader is member ${NEWLDR} (was ${LDR}); killed member is back and ready"

step "3. NEW epoch: refs continue strictly above the old epoch — no reuse, no reset"
start_pf
NEW_REFS=""
for i in $(seq 1 "${N_PER_EPOCH}"); do
  NEW_REFS+="$(ref_of "$(order "${ACCT}" Buy 1 "$(python3 -c "print(${PRICE}-5)")")") "
done
R_NEW="$(agreed refs_all)"
echo "  new-epoch refs: [${NEW_REFS}]"
echo "  members agree next_order_ref = ${R_NEW}"
[[ "${R_NEW}" -gt "${R_OLD}" ]] || fail "next_order_ref did not advance across the epoch (${R_OLD} -> ${R_NEW})"
for r in ${NEW_REFS}; do
  [[ "${r}" -ge "${R_OLD}" ]] || fail "new-epoch order was issued ref ${r} < old-epoch high-water ${R_OLD}: ID REUSED"
done
OVERLAP="$( (tr ' ' '\n' <<<"${OLD_REFS}"; tr ' ' '\n' <<<"${NEW_REFS}") | sed '/^$/d' | sort | uniq -d )"
[[ -z "${OVERLAP}" ]] || fail "ref(s) issued in BOTH epochs: ${OVERLAP}"

step "4. falsification arm: the new-epoch refs are real allocations that trade"
T0="$(agreed trades_all)"
LAST_NEW="$(order "${OTHER}" Sell 1 "$(python3 -c "print(${PRICE}-5)")")"   # crosses a new-epoch buy
echo "  cross against a new-epoch resting order -> ${LAST_NEW}"
T1="$(agreed trades_all)"
[[ "${T1}" -eq "$(( T0 + 2 ))" ]] || fail "the new-epoch orders do not trade — the refs are not live allocations"

echo
echo "[PASS] no cross-epoch id reuse: old epoch topped out at ref ${R_OLD}; every new-epoch ref"
echo "       was >= that mark, the sets are disjoint, the counter is monotonic on all three"
echo "       members, and the new-epoch allocations are live (they trade)."
