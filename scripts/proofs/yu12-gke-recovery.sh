#!/usr/bin/env bash
# yu12-gke-recovery.sh — the system's strongest correctness story, as a committed, re-runnable
# proof: kill a member → it comes back with an EMPTY disk (emptyDir) and rejoins from snapshot +
# log → all three members are byte-identical (order-book hash, position hash, trade counter and
# reference generator all agreed) → and the rejoined node can LATER BECOME LEADER and take writes.
#
# ============================================================================================
# ADR-072 EXPOSURE, FIXED 2026-08-27. Read this before adding an assertion to this file.
#
# The rig has a THIRD writer, replaying sampled TAQ prints as real orders at ~6/s. FIVE assertions
# here were measured against counters that writer moves. STEPS, NOT LINE NUMBERS.
#
#   step 3  `POST == PRE` — THE FLAGSHIP DEFECT, and the one that would have done the most damage.
#           It required the agreed four-quantity state to be IDENTICAL either side of a member
#           rebuild. Replayed flow rewrites the book hash, the position hash, the trade counter and
#           the ref generator continuously, and a rebuild takes tens of seconds, so this is
#           GUARANTEED RED on a correct cluster — reporting "the cluster's agreed state changed
#           across a follower rebuild", i.e. accusing the recovery path of non-determinism at the
#           exact moment the system is doing the hardest correct thing it does.
#
#           NOT DELETED, SCOPED. What the equality meant was "nothing changed across the rebuild",
#           and that is still TRUE and still assertable — of the OPERATOR state. Both operator
#           twins are SNAPSHOTTED (externalOrderRefs at offset 52, externalTradeLegs at 60 —
#           MatchingEngineClusteredService:1499-1500, read back at :1631), so a member restored
#           from an empty disk comes back carrying them; and replayed flow cannot move them by
#           construction. So the invariant now spans the WHOLE destructive sequence — the follower
#           rebuild AND every leader kill in step 4 — and says something the old equality could
#           not: our state survived all of it, unchanged, on all three members.
#           The byte-identity claim itself is carried, as it always really was, by
#           identity_consensus agreeing across three members INCLUDING the rebuilt one.
#
#   step 1  `PRE != BASE` ("traffic changed nothing — the proof would be vacuous"). The tape
#           changes the venue state ~6 times a second, so this held whether or not a single order
#           of ours was applied. An anti-vacuity guard that cannot fail is worse than none: it
#           reads as one. Now asserted on the operator counters, which only our orders move.
#
#   step 5  `T1 == T0 + 2` on traderx_cluster_trades — ACCUSATORY. Any replayed fill in the window
#           inflates it and the proof reports "the cross under the rebuilt leader did not book on
#           all members", blaming the rebuilt leader for the tape. Now assert_order_effects: the
#           operator trade delta bracketed by the operator ref delta, so it is attributable.
#
#   step 5  `R1 > R0` and `R0 >= PRE_REF` (two assertions, one line) on the global ref generator —
#           both EXPOSED IN THE PASSING DIRECTION. The tape advances that counter continuously, so
#           "the reference generator did not go backwards" was satisfied ~6 times a second no
#           matter what the recovery path did. Zero coverage reading as coverage. The id-reuse
#           claim is now made on the OPERATOR ref counter, which stands still unless we write.
#
# THE FOUR TWINS ARE NOT UNIFORM, and this proof is where that matters most: it restarts members
# repeatedly. traderx_cluster_operator_{trades,next_order_ref} are SNAPSHOTTED and survive a
# rebuild, so their cross-member absolutes are legal here. traderx_stp_operator_cancels and
# traderx_band_operator_* are PER-PROCESS (selfTradesPrevented and the band counters are plain
# fields, in neither the snapshot writer nor the reader) — a cross-member absolute on those is a
# statement about UPTIME, unsatisfiable on any epoch where a member restarted, which is every epoch
# this proof produces. NEITHER IS USED HERE. Check the parent before assuming either.
#
# SAMPLING, NOT ONLY CONTAMINATION. identity_consensus reads FOUR quantities with three SEQUENTIAL
# `kubectl exec`s and the tape advances all four between them, so the three samples are from three
# different instants. Measured on the GKE bench 2026-08-27 at 6.13/s: an unretried four-quantity
# read agreed on only 5 of 20 attempts, where the operator twins agreed on 20 of 20. The retry loop
# below is therefore LOAD-BEARING on this tier, not belt-and-braces — DO NOT TRIM IT AS EXCESSIVE.
# A cross-member equality is safe from contamination and exposed to SKEW; those are different
# problems and only one of them is fixed by choosing a better counter.
#
# THE TAPE MUST BE LIVE: step 0 gates the REPORTED rate against ADR-072's 5-20/s band, the last
# step gates the OBSERVED rate (submitted delta / elapsed), which no config field can fake.
#
# THIS PROOF DESTROYS A FOLLOWER AND THEN KILLS LEADERS REPEATEDLY (up to LEAD_ATTEMPTS of them).
# DESTRUCTIVE=1 is required; the default refuses and exits 2 without touching the cluster.
# ============================================================================================
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
#   * our OPERATOR state is asserted unchanged across the entire destructive sequence, and then to
#     advance by exactly our own orders — the id-reuse claim, on a counter no third writer moves.
#
# Usage: DESTRUCTIVE=1 ./yu12-gke-recovery.sh   (GKE cluster up, tape live)
set -euo pipefail

CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu17-6374c110}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18310}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
# MINTED: the tape never trades this symbol, so nothing replayed can cross our orders and the
# operator trade deltas below are exactly ours (the library's standing rule about not leaving an
# order resting on a REPLAYED symbol is satisfied by construction).
TICKER="${TICKER:-RCV$(date +%H%M%S)}"
PRICE="${PRICE:-120.00}"
LEAD_ATTEMPTS="${LEAD_ATTEMPTS:-6}"   # leader kills allowed while waiting for the rejoined node to win
DESTRUCTIVE="${DESTRUCTIVE:-0}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-consensus-readings.sh"
. "${here}/lib-gke-replay-gates.sh"

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
# THE BYTE-IDENTITY CLAIM, and the only reading in this file that still uses the global counters —
# correctly, because "the members agree" is a determinism claim that no third writer can disturb.
#
# Retried, and on this tier the retry is doing REAL WORK rather than holding tries in reserve: the
# three samples are three sequential execs and the tape moves all four quantities between them
# (measured 5/20 unretried agreement at 6.13/s, GKE bench, 2026-08-27). PERSISTENT disagreement is
# the failure; a single skewed sample is not, and reporting one as divergence is how a correct
# cluster gets accused.
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

if [[ "${SELFTEST:-0}" == "1" ]]; then gates_selftest; exit $?; fi

require_destructive \
  "DESTROYS A FOLLOWER to an empty disk (step 2) and then KILLS LEADERS REPEATEDLY (step 4, up to
       ${LEAD_ATTEMPTS} of them) to force elections, on what may be a shared rig." \
  "resting orders + positions -> follower destroyed to an empty disk -> three-member byte-identity
                on the rebuilt cluster -> the rebuilt member wins leadership -> it takes writes and books a
                cross on all three, with our operator state intact across the whole sequence." \
  "LEAD_ATTEMPTS=${LEAD_ATTEMPTS}"

# ---------------------------------------------------------------------------------------------
step "0. preflight: the divergence rule, a live tape, three members agreed BEFORE any traffic"
require_uniform_image "${IMAGE}"
require_tape_live
PRESSURE0="$(pressure_row)"
RUN_T0=${SECONDS}
start_pf
for acct in "${ACCT}" "${OTHER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
BASE="$(identity_consensus)"
# /seed sequences TYPE_ACCOUNT_CONTROL and TYPE_SECURITY_CONTROL, never TYPE_ORDER_NEW, so it
# consumes no order ref and the operator brackets start clean after it.
OPR_BASE="$(quiesced_order_refs)"; OPT_BASE="$(quiesced_trades)"
echo "  agreed [orderHash posHash trades nextRef] = [${BASE}]   ticker ${TICKER}"
echo "  operator baseline: refs ${OPR_BASE}, trade legs ${OPT_BASE}"

step "1. build real state: resting orders AND positions (so BOTH hashes carry information)"
order "${ACCT}"  Buy  7 "${PRICE}" >/dev/null           # rests
order "${OTHER}" Sell 4 "${PRICE}" >/dev/null           # crosses: trades + positions move
order "${OTHER}" Sell 3 "$(python3 -c "print(${PRICE}+5)")" >/dev/null   # rests away from touch
PRE="$(identity_consensus)"
OPR_PRE="$(quiesced_order_refs)"; OPT_PRE="$(quiesced_trades)"
echo "  pre-kill agreed state: [${PRE}]"
# ANTI-VACUITY, on counters only OUR orders move. The old form compared the venue-wide agreed state
# to its baseline, which the tape changes ~6 times a second — so it held on a run where every one
# of these three orders had been silently dropped. Three orders in, two trade legs out (one cross,
# one leg per side): assert_order_effects brackets the trade delta by the ref delta so the trade
# reading is attributable to us and not to a concurrent operator.
assert_order_effects "${OPR_BASE}" "${OPR_PRE}" 3 "${OPT_BASE}" "${OPT_PRE}" 2 \
  "the state this proof is about to destroy and rebuild was never actually built"
echo "  operator refs ${OPR_BASE} -> ${OPR_PRE} (3 orders), trade legs ${OPT_BASE} -> ${OPT_PRE} (one cross)"

step "2. destroy a FOLLOWER — emptyDir, so it returns with an EMPTY disk"
LDR="$(leader)" || fail "no leader found"
VICTIM=""
for m in 0 1 2; do [[ "${m}" != "${LDR}" ]] && { VICTIM="${m}"; break; }; done
VICTIM_POD="order-matcher-cluster-${VICTIM}"
VICTIM_UID="$(member_pod_uid "${VICTIM}")"
echo "  leader is member ${LDR}; destroying follower ${VICTIM_POD}"
${K} delete pod "${VICTIM_POD}" --wait=true >/dev/null
# `kubectl wait --for=condition=Ready` does NOT wait for a pod to be CREATED. Against a name that
# does not exist it returns `NotFound` IMMEDIATELY, and the --timeout never applies at all. The line
# above just deleted the pod, so there is ALWAYS a window before the controller recreates it, and
# how wide that window is depends on how busy the box is -- so this passes until it does not, then
# reports "never became Ready" about a pod that had not yet been asked to exist. Caught in
# yu17-swap-netting on 2026-08-14; the same shape is here. Wait for EXISTENCE first.
for _ in $(seq 1 150); do
  ${K} get pod "${VICTIM_POD}" >/dev/null 2>&1 && break
  sleep 2
done
${K} wait --for=condition=Ready "pod/${VICTIM_POD}" --timeout=600s >/dev/null
[[ "$(member_pod_uid "${VICTIM}")" != "${VICTIM_UID}" ]] \
  || fail "member ${VICTIM} still has its ORIGINAL pod uid after the delete — it was never actually
  replaced, so nothing below tests a rebuild from an empty disk."
echo "  ${VICTIM_POD} is back (fresh pod, empty disk) — rebuilding from snapshot + log"

step "3. the rebuilt member converges to BYTE-IDENTITY with the survivors"
# THE CONVERGENCE CLAIM IS identity_consensus ITSELF. It does not return until all three members
# report the same order hash, position hash, trade counter and ref generator — and after step 2
# that set INCLUDES the member restored from an empty disk. That is the whole claim.
#
# The old `POST == PRE` on top of it was a different and now-false statement: that the venue's
# agreed state had not MOVED across the rebuild. Replayed flow moves it continuously and a rebuild
# takes tens of seconds, so it was guaranteed red — and it read as "the recovery path is
# non-deterministic". What it was really asserting, once scoped to state this proof owns, is below
# and holds across the entire destructive sequence.
POST="$(identity_consensus)"
echo "  post-rejoin agreed state: [${POST}]   (moved from [${PRE}] — the tape, not the rebuild)"
OPR_POST="$(quiesced_order_refs)"; OPT_POST="$(quiesced_trades)"
# THE SURVIVING INVARIANT, and it is legal here for a specific reason: both operator twins are
# SNAPSHOTTED on both halves, so the member restored from an empty disk comes back carrying them
# and their cross-member absolutes remain comparable. A PER-PROCESS twin (traderx_stp_operator_cancels,
# traderx_band_operator_*) would be unsatisfiable at exactly this point — its cross-member absolute
# is a statement about uptime, and this step just reset one member's.
[[ "${OPR_POST}" == "${OPR_PRE}" && "${OPT_POST}" == "${OPT_PRE}" ]] \
  || fail "our OPERATOR state changed across the follower rebuild: refs ${OPR_PRE} -> ${OPR_POST}, trade legs
  ${OPT_PRE} -> ${OPT_POST}. This proof submitted nothing in that window and replayed flow cannot move these
  counters, so it is one of exactly two things — and check the SECOND before reporting the first:
    1. the recovery path lost or duplicated state this proof put there (the serious reading, and the
       claim the old venue-wide equality was reaching for and could no longer make); or
    2. a CONCURRENT OPERATOR wrote on this shared rig — the algo engine, another lane's proof, or a
       person with curl. These counters are global over operator writers, not private to this run.
  A rising delta with no red anywhere else is far more likely to be (2)."
echo "  operator state UNCHANGED across the rebuild: refs ${OPR_POST}, trade legs ${OPT_POST}"

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
S0="$(identity_consensus)"
OPR_0="$(quiesced_order_refs)"; OPT_0="$(quiesced_trades)"
# THE INVARIANT ACROSS EVERY LEADER KILL IN STEP 4 TOO, not only the follower rebuild. Nothing of
# ours was submitted between step 3 and here, so any movement is state lost or duplicated by an
# election — and this is the id-reuse claim as well, made on a counter that stands still unless we
# write it. The old form (`R1 > R0 && R0 >= PRE_REF` on the GLOBAL generator) was satisfied ~6
# times a second by the tape and could never have failed.
[[ "${OPR_0}" == "${OPR_POST}" && "${OPT_0}" == "${OPT_POST}" ]] \
  || fail "our OPERATOR state changed across the leadership elections in step 4: refs ${OPR_POST} -> ${OPR_0},
  trade legs ${OPT_POST} -> ${OPT_0}. This proof submitted nothing in that window. A ref generator that moved
  BACKWARDS here is id reuse and is the serious reading; one that moved FORWARD is more likely a
  concurrent operator on this shared rig (the algo engine, another lane, a person with curl) than a
  phantom order — these counters are global over operator writers. Check the direction first."
CLOSE="$(order "${ACCT}" Buy 3 "$(python3 -c "print(${PRICE}+5)")")"   # crosses the resting +5 sell
echo "  buy 3 @ +5 under the rebuilt leader -> ${CLOSE}"
S1="$(identity_consensus)"
OPR_1="$(quiesced_order_refs)"; OPT_1="$(quiesced_trades)"
echo "  agreed state: [${S0}] -> [${S1}]"
assert_order_effects "${OPR_0}" "${OPR_1}" 1 "${OPT_0}" "${OPT_1}" 2 \
  "the cross under the rebuilt leader did not book on all members"
echo "  operator refs ${OPR_0} -> ${OPR_1}, trade legs ${OPT_0} -> ${OPT_1} (2 legs, one match)"
(( OPR_1 > OPR_BASE )) \
  || fail "the operator ref generator (${OPR_BASE} -> ${OPR_1}) did not advance across the whole run: id reuse
  is possible after a rebuild."

step "6. the write pressure this run actually ran under"
assert_observed_rate "$(( SECONDS - RUN_T0 ))" "cluster recovery"
# three orders in step 1 and the closing cross in step 5 -- counted from the call sites, and
# matched +4 on both GKE runs recorded in the issue file.
print_pressure "${PRESSURE0}" "$(pressure_row)" 4

echo
echo "[PASS] cluster recovery: a member destroyed to an empty disk rejoined to byte-identity on"
echo "       order hash, position hash, trades and nextOrderRef; then won leadership and booked a"
echo "       cross on all three members — with this proof's own operator state unchanged across"
echo "       the rebuild AND every election, and the operator ref generator strictly monotonic."
echo
echo "       NOT SHOWN, so that a reader citing this banner is not citing more than was tested:"
echo "         * no claim under concurrent OPERATOR load. The scenario is sequential, and a second"
echo "           operator writing during the run would move the very counters the volume claims"
echo "           bracket and the unchanged-across-the-rebuild equalities rest on. This proof does"
echo "           not even check quiescence at the start (failover-transparency and restore-from-gcs"
echo "           do) — and that check would only cover a MOMENT, never the run."
echo "         * byte-identity is four quantities agreed, not the books compared order by order."
echo "         * nothing about a WIPED INCARNATION. Every kill here is a leadership term change;"
echo "           refs restart at 1 on a mint and this proof never performs one."
