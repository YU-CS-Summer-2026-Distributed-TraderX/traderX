#!/usr/bin/env bash
# yu12-gke-cross-epoch-idreuse.sh — proves the reference generator does not reissue an order id
# across a FAILOVER: leader killed, new leader elected, and orderRefs continue STRICTLY ABOVE
# everything issued before the kill — no overlap, no reset, agreed by all members.
#
# ============================================================================================
# READ THIS FIRST: THE NAME OVERCLAIMS, AND THE CORRECTION IS THE POINT (2026-08-27).
#
# This file was called "cross-epoch id reuse" and its header said the generator "NEVER reissues an
# order id from a prior epoch". THAT IS NOT A PROPERTY OF THIS SYSTEM, and the proof never tested
# it. Two different things were being called an epoch:
#
#   a LEADERSHIP TERM   — what this proof actually produces, by killing the leader. nextOrderRef is
#                         snapshotted (the YU11->YU12 fix genuinely under test here), the restarted
#                         pod reads the same CLUSTER_EPOCH, and refs continue. This proof shows it.
#   a WIPED INCARNATION — a fresh epoch mint. nextOrderRef is initialised to 1
#                         (MatchingEngineClusteredService:502, :716), so refs DO restart at 1 and DO
#                         collide with the previous incarnation's. This proof never performs one and
#                         shows nothing whatever about it.
#
# What keeps two incarnations apart is the EPOCH QUALIFIER on the read-model id, not the generator.
# OrderNatsPublisher:20, verbatim:
#
#     "orderRef restarts at 1 on a fresh cluster incarnation, so a table keyed on the bare ref
#      collides across epochs -- partially, silently. The read-model key is therefore
#      epoch + \"-\" + orderRef ... stable across FAILOVER (orderRef does not reset on failover),
#      and bumped together with wiping the DB on a fresh incarnation -- they are one artifact."
#
# and MatchingEngineClusteredService:1278 is blunt: "Nothing here makes ids unique ACROSS a wiped
# epoch." So the hazard this file's own preamble cites — trade-processor dedup eating real trades
# on 2026-07-22 — arises at a MINT, which this proof does not perform.
#
# The assertions were all sound for what the proof DOES; the claim wrapped around them was not.
# Step 3 now also asserts the half that is testable here and was never checked: the epoch qualifier
# is the SAME on both sides of the kill, which is exactly OrderNatsPublisher's "stable across
# failover". The mint case is covered by NO proof we have — see the issue file's residual. Found by
# asking what this proof establishes, not whether its counters were clean.
#
# Why this is its own proof (and not just a line in the recovery/failover scripts): id reuse is
# the failure mode that silently corrupts every downstream identity — the ClOrdId ledger, the
# epoch-qualified read-model ids, trade-processor dedup ("Duplicate trade delivery ignored" ate
# real trades on 2026-07-22 for exactly this class of bug). The nextOrderRef-in-snapshot fix
# (moved YU11→YU12) is the change under test; this script is its standing regression proof.
#
# ============================================================================================
# ADR-072 EXPOSURE, FIXED 2026-08-27. Read this before adding an assertion to this file.
#
# The rig has a THIRD writer, replaying sampled TAQ prints as real orders at ~6/s. Two assertions
# here were measured against counters that writer moves. STEPS, NOT LINE NUMBERS — a header
# insertion renumbers the file and a stale number sends the next reader to the wrong assertion.
#
#   step 3  `R_NEW > R_OLD` on traderx_cluster_next_order_ref — EXPOSED IN THE PASSING DIRECTION.
#           The tape advances that counter ~6 times a second, so the inequality held whether or
#           not this proof's own orders were allocated anything. Zero coverage that read as
#           coverage, and it would never have printed a red to tell anyone. Replaced by an
#           OPERATOR-ref delta, which only this proof's orders can move.
#   step 4  `T1 == T0 + 2` on traderx_cluster_trades — EXPOSED IN THE ACCUSATORY DIRECTION. Any
#           replayed fill inside the window inflates it, and the proof would report "the new-epoch
#           orders do not trade — the refs are not live allocations", naming id reuse for what the
#           tape did. Replaced by assert_order_effects: the operator TRADE delta bracketed by the
#           operator REF delta, so the trade reading is attributable to our one order.
#
# WHAT WAS ALREADY SOUND, AND STAYS — this is the part worth not re-deriving:
#   * every new-epoch ref >= the old epoch's high-water. This is an IDENTITY claim on refs THIS
#     proof was handed, measured against a venue high-water mark. The tape raises that mark, which
#     makes the bar STRICTER, never wrong. (It is also N assertions, not one — it is a loop.)
#   * the old and new ref SETS are disjoint. Pure identity on our own refs; no counter involved.
#   These two carry the actual id-reuse claim, which is why this proof was never as exposed as its
#   siblings: its central assertion was already the shape the library asks for.
#
# THE OPERATOR TWINS SURVIVE THE FAILOVER, and that is not incidental. Both halves of
# traderx_cluster_operator_next_order_ref and _operator_trades are SNAPSHOTTED (externalOrderRefs
# at snapshot offset 52, externalTradeLegs at 60 — MatchingEngineClusteredService:1499-1500, read
# back at :1631), so the member this proof kills comes back with them intact and they can still be
# quiesced across all three afterwards. A PER-PROCESS twin here (traderx_stp_operator_cancels,
# traderx_band_operator_*) would have substituted a fresh bug for the one being removed: its
# cross-member ABSOLUTE is unsatisfiable on any epoch where a member restarted, which is exactly
# what step 2 does. THE FOUR TWINS ARE NOT UNIFORM — check the parent before assuming either.
#
# A RETRY BURNS A REF BY DESIGN. The ref is consumed in MatchingEngineClusteredService
# (`event.orderRef = (int) nextOrderRef++`) BEFORE the engine answers idempotently, so a resend
# allocates a ref and books nothing. assert_order_effects' exact ref delta is therefore only legal
# in a window with no failover in it — which is why it is used in step 4 (post-election, quiet) and
# NOT across step 2. The retry loop below now keeps the clientOrderId STABLE across attempts so a
# resend is idempotent at the engine; it previously minted a fresh id per attempt, which made every
# retry a genuinely new order and would have broken the step-4 bracket on any flaky send.
#
# THE TAPE MUST BE LIVE. Step 0 gates the REPORTED rate against ADR-072's 5-20/s band and the last
# step gates the OBSERVED rate (submitted delta / elapsed), which a config field cannot fake. A
# green with the tape stopped re-proves none of the above.
#
# THIS PROOF KILLS A CLUSTER MEMBER and now says so first: DESTRUCTIVE=1 is required.
# ============================================================================================
#
# Method — assert at both ends of the failover, from the members' own counters:
#   1. issue orders in the OLD epoch; record every orderRef the gateway returned AND the members'
#      agreed next_order_ref high-water mark R_old;
#   2. kill the leader; wait for the new epoch (new leader, all members back);
#   3. issue orders once the new leader is up; every returned orderRef must be >= R_old, the
#      operator ref counter must have advanced by our order count, the old and new ref SETS must be
#      disjoint, and the read model's EPOCH QUALIFIER must be unchanged across the kill;
#   4. falsification arm: the new-epoch orders BOOK — proving the refs are real allocations, not a
#      counter that wandered upward while allocation restarted from 1.
#
# WHY GKE: the scenario is an election; kind's starved CPUs make election behaviour meaningless.
# Usage: DESTRUCTIVE=1 ./yu12-gke-cross-epoch-idreuse.sh   (GKE cluster up, tape live)
set -euo pipefail

CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu17-6374c110}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18330}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
# A MINTED ticker: the tape never trades this symbol, so nothing replayed can cross our orders and
# the operator trade delta in step 4 is exactly ours. (The library's standing rule — a proof must
# not leave an order resting on a REPLAYED symbol — is satisfied by construction here.)
TICKER="${TICKER:-EPO$(date +%H%M%S)}"
PRICE="${PRICE:-105.00}"
N_PER_EPOCH="${N_PER_EPOCH:-10}"
DESTRUCTIVE="${DESTRUCTIVE:-0}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-consensus-readings.sh"
. "${here}/lib-gke-replay-gates.sh"

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
# THE GLOBAL counter, for the CROSS-MEMBER AGREEMENT check and the high-water bar, and nothing
# else. "The members agree" is a determinism claim a third writer cannot touch; the high-water is
# a bar our own refs must clear, which foreign flow only raises.
#
# WRITTEN TO `_agreed`'s SIGNATURE ON PURPOSE — a reader taking a MEMBER ORDINAL, not one returning
# all three. This file used to hand-roll its own retry around a space-joined triple, and the
# ADR-072 lane's sharpest formulation of the sampling defect is that THE READERS AT RISK ARE EXACTLY
# THE ONES THAT HAND-ROLL THEIR COMPARISON instead of calling in to the library, which has always
# retried. Using `_agreed` also buys the fast-fail that a hand-rolled loop cannot have: if all three
# members answer -1 it says "the metric is absent, this build predates the ADR-072 operator
# counters" immediately, instead of burning two minutes waiting for agreement on a disagreement that
# is not happening.
#
# The retry is LOAD-BEARING on this tier, not belt-and-braces: three sequential `kubectl exec`s at
# ~0.35s each against a 6/s tape means a member that sampled earlier reads lower and is reported as
# disagreeing. Measured on the GKE bench 2026-08-27, a four-quantity read agreed on 5 of 20
# unretried attempts. The CLAIM is sound; the MEASUREMENT is not, without the loop.
venue_refs() { member_metric "$1" traderx_cluster_next_order_ref 2>/dev/null; }
quiesced_venue_refs() { _agreed venue_refs "the venue-wide order-ref high-water mark"; }
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

# The clientOrderId is minted ONCE and reused across every attempt, so a resend is idempotent at
# the engine instead of becoming a second order. (It still BURNS a ref — the ref is allocated
# before the idempotency check — which is why no exact ref delta spans a window containing a kill.)
order() { # order <account> <side> <qty> <price> [cid-suffix] -> body
  local out t=0 cid="epo-$$-${5:-${RANDOM}}"
  until out="$(curl -s --max-time 10 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
      -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4,\"clientOrderId\":\"${cid}\"}" 2>/dev/null)" \
      && [[ "${out}" == *'"orderRef"'* ]]; do
    t=$((t+1)); [[ ${t} -lt 60 ]] || fail "order never acked (clientOrderId ${cid})"
    sleep 1
  done
  echo "${out}"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

if [[ "${SELFTEST:-0}" == "1" ]]; then gates_selftest; exit $?; fi

require_destructive \
  "KILLS THE CLUSTER LEADER (step 2) to force an election, on what may be a shared rig." \
  "old-epoch orders -> leader kill -> new-epoch refs strictly above the old high-water, sets
                disjoint, operator ref delta exactly ours, and the new refs shown to be live allocations." \
  "N_PER_EPOCH=10"

# ---------------------------------------------------------------------------------------------
step "0. preflight: the divergence rule, a live tape, gateway up, seeded"
require_uniform_image "${IMAGE}"
require_tape_live
# The epoch-qualifier assertion in step 3 reads the order read model, so the reader must prove it
# can READ before anything depends on it. A route answering "" or [] for every account is
# indistinguishable from "not visible yet" until a timeout, and would then be reported as a verdict
# about id reuse rather than as the probe failing.
require_read_model "${ACCT}"
PRESSURE0="$(pressure_row)"
RUN_T0=${SECONDS}
start_pf
for acct in "${ACCT}" "${OTHER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
LDR="$(leader)" || fail "no leader"
# /seed sequences TYPE_ACCOUNT_CONTROL and TYPE_SECURITY_CONTROL, never TYPE_ORDER_NEW, so it does
# not consume an order ref — the operator brackets below start clean after it.
OPR_BASE="$(quiesced_order_refs)"
echo "[ok] leader is member ${LDR}; ticker ${TICKER}; operator refs at ${OPR_BASE}"

step "1. OLD epoch: issue ${N_PER_EPOCH} orders, record their refs and the high-water mark"
OLD_REFS=""
for i in $(seq 1 "${N_PER_EPOCH}"); do
  OLD_REFS+="$(ref_of "$(order "${ACCT}" Buy 1 "${PRICE}" "old-${i}")") "
done
R_OLD="$(quiesced_venue_refs)"
OPR_OLD="$(quiesced_order_refs)"
echo "  old-epoch refs: [${OLD_REFS}]"
echo "  members agree next_order_ref = ${R_OLD} (venue-wide); operator refs ${OPR_BASE} -> ${OPR_OLD}"
# ATTRIBUTABLE, where the old `R_NEW > R_OLD` was not: only this proof's orders move this counter.
(( OPR_OLD - OPR_BASE == N_PER_EPOCH )) \
  || fail "the operator ref generator moved by $(( OPR_OLD - OPR_BASE )), not the ${N_PER_EPOCH} orders this
  proof submitted. Either one of ours was never sequenced, or another OPERATOR writer (the algo
  engine, another lane's proof, a human with curl) is on this rig — the tape is excluded by
  construction, so this is not the replay."

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
  NEW_REFS+="$(ref_of "$(order "${ACCT}" Buy 1 "$(python3 -c "print(${PRICE}-5)")" "new-${i}")") "
done
R_NEW="$(quiesced_venue_refs)"
OPR_NEW="$(quiesced_order_refs)"
echo "  new-epoch refs: [${NEW_REFS}]"
echo "  members agree next_order_ref = ${R_NEW} (venue-wide); operator refs ${OPR_OLD} -> ${OPR_NEW}"
# THE ADVANCE CLAIM, on the counter the tape cannot move. The old form asserted the GLOBAL counter
# advanced, which replayed flow satisfies ~6 times a second whether or not a single order of ours
# was allocated anything. A retry across the election burns a ref without booking, so this is a
# FLOOR on our own allocations rather than an equality — and it is bracketed above by the per-ref
# identity checks, which is what keeps it from going vacuously green.
(( OPR_NEW - OPR_OLD >= N_PER_EPOCH )) \
  || fail "the operator ref generator moved by $(( OPR_NEW - OPR_OLD )) across the new epoch, fewer than the
  ${N_PER_EPOCH} orders submitted (${OPR_OLD} -> ${OPR_NEW}). Refs are allocated on apply, so orders that were
  acked but never sequenced is the one thing this cannot be — the generator RESET is what it looks like."
# IDENTITY, and the real id-reuse claim: N assertions, one per ref this proof was handed.
for r in ${NEW_REFS}; do
  [[ "${r}" -ge "${R_OLD}" ]] || fail "new-epoch order was issued ref ${r} < old-epoch high-water ${R_OLD}: ID REUSED"
done
OVERLAP="$( (tr ' ' '\n' <<<"${OLD_REFS}"; tr ' ' '\n' <<<"${NEW_REFS}") | sed '/^$/d' | sort | uniq -d )"
[[ -z "${OVERLAP}" ]] || fail "ref(s) issued on BOTH sides of the failover: ${OVERLAP}"
echo "  every post-kill ref >= ${R_OLD}, and the two sets are disjoint"
# THE HALF THIS PROOF CAN ACTUALLY TEST ABOUT EPOCHS, and it was never checked. The read-model id is
# <epoch>-<orderRef> and the qualifier is what separates incarnations. OrderNatsPublisher's design
# claim is that it is STABLE ACROSS FAILOVER (the restarted pod reads the same CLUSTER_EPOCH from
# the manifest) and bumped only when the DB is wiped. If it HAD changed here, the refs continuing
# would prove far less than it appears to: the two halves would sit in different keyspaces and could
# not have collided whatever the generator did.
E_OLD="$(order_epoch_of "${ACCT}" "${OLD_REFS%% *}")"
E_NEW="$(order_epoch_of "${ACCT}" "${NEW_REFS%% *}")"
[[ -n "${E_OLD}" && -n "${E_NEW}" ]] \
  || fail "could not read the epoch qualifier off the read model (old '${E_OLD}', new '${E_NEW}').
  That is the PROBE failing to read, NOT a verdict about id reuse — check trade-processor and its
  NATS feed, and that account ${ACCT} is seeded (orderbook.accountid is a FK; an unseeded account
  returns zero rows for every order ever written)."
[[ "${E_OLD}" == "${E_NEW}" ]] \
  || fail "the epoch qualifier changed across the leader kill (${E_OLD} -> ${E_NEW}). A failover must NOT
  bump it — CLUSTER_EPOCH comes from the manifest and the restarted pod reads the same value. If it
  moved, this rig performed a fresh INCARNATION rather than a failover, and the ref continuity
  asserted above proves nothing about reuse: refs restart at 1 on a mint, by design."
echo "  epoch qualifier UNCHANGED across the kill (${E_OLD}) — a failover, not a fresh incarnation"

step "4. falsification arm: the new-epoch refs are real allocations that trade"
# The window is post-election and contains exactly one order of ours, so the exact ref delta is
# legal here (no failover inside it to burn a ref on a retry). assert_order_effects brackets the
# operator TRADE delta by the operator REF delta: "exactly my one order was sequenced in this
# window, and it had exactly this trade effect". Either half alone is the vacuous form.
R_B="$(quiesced_order_refs)"; T_B="$(quiesced_trades)"
LAST_NEW="$(order "${OTHER}" Sell 1 "$(python3 -c "print(${PRICE}-5)")" "cross")"   # crosses a new-epoch buy
echo "  cross against a new-epoch resting order -> ${LAST_NEW}"
R_A="$(quiesced_order_refs)"; T_A="$(quiesced_trades)"
assert_order_effects "${R_B}" "${R_A}" 1 "${T_B}" "${T_A}" 2 \
  "the new-epoch orders do not trade — the refs are not live allocations"
echo "  operator refs ${R_B} -> ${R_A}, operator trades ${T_B} -> ${T_A} (2 legs, one match)"

step "5. the write pressure this run actually ran under"
assert_observed_rate "$(( SECONDS - RUN_T0 ))" "cross-epoch id reuse"
print_pressure "${PRESSURE0}" "$(pressure_row)"

echo
echo "[PASS] no id reuse ACROSS A FAILOVER: refs topped out at ${R_OLD} before the kill; every ref"
echo "       issued after it was >= that mark, the sets are disjoint, the epoch qualifier was"
echo "       unchanged (${E_OLD}), the operator ref generator advanced by our own orders across the"
echo "       election, and the post-kill allocations are live (they trade)."
echo
echo "       NOT SHOWN HERE, and not shown by any proof we have: id separation across a WIPED"
echo "       INCARNATION. Refs restart at 1 on a mint by design; the epoch qualifier is what keeps"
echo "       those rows apart. Do not cite this run as cross-epoch coverage."
