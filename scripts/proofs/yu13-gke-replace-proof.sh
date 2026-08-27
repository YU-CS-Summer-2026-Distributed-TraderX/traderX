#!/usr/bin/env bash
# yu13-gke-replace-proof.sh — the three things atomic replace (ADR-058) still needed proving on a
#
# !!! THIS PROOF IS KNOWN-EXPOSED TO ADR-072's REPLAYED ORDER FLOW AND HAS NOT BEEN FIXED
# !!! (2026-08-27; enumeration CORRECTED the same day, see "how this list was got wrong" below)
#
# READ THIS BEFORE YOU DEBUG A RED RUN, because the failure will not look like this cause: it will
# say "expected exactly one order to leave the book", or "member 0 booked a self-trade", or "the
# rebuilt member did not converge" -- and the replace under test will have worked perfectly.
#
# Since ADR-072 the rig has a THIRD writer replaying sampled TAQ prints as real orders at ~6/s.
#
# EIGHT assertions claim an exact delta or an unchanged absolute over a window, on counters every
# writer moves. All eight fail in the ACCUSATORY direction -- naming a component that did nothing:
#
#   :184  BEFORE == AFTER          traderx_book_open_orders   venue-wide, NO twin
#   :210  $((BEFORE-1)) -eq AFTER  traderx_book_open_orders   venue-wide, NO twin
#   :214  s1 -eq s0 + 1  per mem   traderx_stp_cancels        twin EXISTS since 6374c110
#   :216  t1 -eq t0      per mem   traderx_cluster_trades     twin EXISTS  <- same loop as :214
#   :261  PRE_KILL == POST_KILL    book digest, across a member KILL AND REBUILD -- seconds during
#                                  which replayed flow is guaranteed to move the venue
#   :275  t1 -eq t0 + 2  per mem   traderx_cluster_trades     twin EXISTS
#   :290  BEFORE == AFTER          book digest (depth AND hash)
#   :298  t1 -eq t0 + 2  per mem   traderx_cluster_trades     twin EXISTS
#
# ONE MORE fails in the opposite and worse direction -- it CANNOT FAIL:
#
#   :183  BEFORE != AFTER          "the replace changed nothing on the members". Replayed flow
#                                  rewrites the digest continuously, so this holds whether or not
#                                  the replace did anything. Zero coverage that reads as coverage.
#                                  Delete it; :180's orderRef identity is the real claim.
#
# SOUND, leave alone: :222-:224, the `uniq_one` cross-member agreement checks. "The members agree"
# is true no matter who wrote the orders. Note trades_all() feeds BOTH those and the exposed
# per-member deltas -- the same helper, sound in one line and exposed in the next.
#
# HOW THIS LIST WAS GOT WRONG, TWICE, WHICH IS THE USEFUL PART:
#   * It first said SIX. It was built by enumerating readers of `traderx_book_open_orders` -- a
#     search by METRIC NAME, which cannot see a site whose exposure comes through a different
#     counter.
#   * It then said SEVEN. :216 was missed because it sits in the same `for m in 0 1 2` loop as
#     :214, two lines below, and the loop was read as ONE site. A loop is N assertions.
#   * It is now NINE, found by enumerating ASSERTIONS (`grep -nE '^\s*\[\[|^\s*\(\('`) and
#     classifying each, rather than by chasing metrics. :261 and :183 appear in no metric-name
#     search at all: both compare a whole digest string.
#   Enumerate what the file ASSERTS, not what it READS. Satisfy yourself there is no tenth.
#
# WHY IT IS STILL LIKE THIS: this file is not in run-proofs.sh, so nothing on the kind rig can red
# it, and its branch has never run under live replay -- nothing has ever printed a red from any of
# the nine. FIX ALL OF THEM IN ONE CHANGE, on a tier that can run it: fixing a subset leaves the
# rest red, which is unexercised diff for no gain.
#
# THE FIX, in the order lib-consensus-readings.sh ranks them:
#   1. ASK THE ORDER. :184/:210/:290/:261 are all standing in for "THIS order did X" -- the order's
#      own state says it, needs no counter, and no other writer can move it. See yu13-cancel-ingress
#      `52752df4` and `d26d9851` for the same rewrite done on the same class; copy that shape.
#   2. THE OPERATOR TWIN, per member, for the four counter sites: `traderx_cluster_operator_trades`
#      (:216/:275/:298) and `traderx_stp_operator_cancels` (:214). Both exist and both are
#      per-process -- see the persistence rule above the twins in ClusterNodeMain BEFORE assuming
#      that of any future one.
#   3. DO NOT build `traderx_book_operator_open_orders`. It is a gauge over resting state, so there
#      is no monotonic external contribution to subtract, and its two call sites are identity
#      claims anyway (fix 1).
#
# See scripts/proofs/lib-consensus-readings.sh (the "SHAPE OF THE READING" block) and
# issues/open/five-gke-proofs-read-a-global-counter-that-replayed-flow-now-moves.md for the
# enumeration of all seventeen readers, of which this file is the only exposed one.
#
# real cluster, run on GKE because kind cannot carry them.
#
# WHY GKE. Replace is the least-proven and most complex of the member bundle: multiple state
# mutations inside ONE apply, which is exactly where unit tests are weakest. It needs a real
# cluster. kind is not one: four nodes idling at 145-205% CPU each on an 11-CPU Docker VM is
# ~60-75% utilisation doing nothing (Aeron busy-spin), which is what produced the 2.3x throughput
# spread, the SnapshotBarrier/ThreeMemberCluster timing flakes, and finally a kube-apiserver that
# fell over mid-proof. That is a diagnosis, not bad luck.
#
# WHAT THIS ESTABLISHES, beyond "the command works":
#   1. Egress ack correlation when ONE input produces cancel-plus-add. A replace that becomes
#      marketable into the participant's OWN resting order emits, in a single apply, its own
#      order-update AND an unsolicited STP cancel for a DIFFERENT order. The caller must get the
#      replace's outcome, never the cancel. This is the one contract a unit test cannot check,
#      because it is a property of the gateway's ack correlation, not of the engine.
#   2. Three-member state identity through a replace — depth, book hash and the reference
#      generator, agreed by all three, at every step.
#   3. A replace surviving a snapshot/restore boundary mid-sequence — snapshot taken AFTER the
#      replace, more replaces applied after it, then a member destroyed and rebuilt from nothing
#      (emptyDir: the pod comes back with an empty disk and rejoins from snapshot + log). The
#      restored order is then traded AT ITS REPLACED PRICE AND QUANTITY, which is what makes the
#      assertion falsifiable: if the replace had not survived, the closing order would not cross.
#
# The divergence rule is applied on the way in — every member running the target image AND ready,
# then the members agreed, BEFORE any traffic. That rule was earned on this exact change: a
# rolling window is a divergence window while members compute different functions of the same log.
#
# Assertion end: the engine's own per-member counters and book digests. There is no order read
# model (`orderbook` holds 0 rows for every order ever), and the trades read model is deliberately
# not deployed for this proof — booked trades are asserted on the engine's trade counter, which is
# authoritative and is simultaneously a cross-member agreement check.
set -euo pipefail

CTX="${CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu13-replace}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18210}"
SELF="${SELF:-42422}"
OTHER="${OTHER:-22214}"
TICKER="${TICKER:-RPL$(date +%H%M%S)}"
PRICE="${PRICE:-150.00}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
px() { python3 -c "print(${PRICE} + $1)"; }

member_metric() { # member_metric <ordinal> <metric-prefix>
  ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'
}
book() { # "<openOrders> <orderHash>"
  ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_open_orders/{d=$2} /^traderx_book_order_hash/{h=$2} END{print d, h}'
}
# Retried: members apply the committed tail at slightly different times and a single sample can
# catch one mid-apply, which looks exactly like a determinism failure and is not one. PERSISTENT
# disagreement is the failure.
digest_consensus() {
  local b0 b1 b2 i
  for i in $(seq 1 40); do
    b0="$(book 0)"; b1="$(book 1)"; b2="$(book 2)"
    # SHAPE, not equality alone. book()'s awk ends in END{print d, h}, which fires on NO INPUT with
    # both variables unset and prints a single space; " " == " " == " " is agreement, so three
    # unreachable members were reported as an agreed book digest — and the agreed digest is this
    # proof's primary assertion.
    #
    # This file already knew the answer: uniq_one() below is used correctly at the refs, trades and
    # STP counter checks. It was simply never applied to the digest, which is the one that matters
    # most. A shape test is used rather than uniq_one here because the digest is two fields and the
    # hash is routinely NEGATIVE, so the shape carries information uniq_one cannot.
    if [[ "${b0}" =~ ^[0-9]+\ -?[0-9]+$ \
       && "${b0}" == "${b1}" && "${b1}" == "${b2}" ]]; then echo "${b0}"; return 0; fi
    sleep 1
  done
  fail "members never agreed on the book: [${b0}] [${b1}] [${b2}]
  (all-blank readings mean the members were UNREACHABLE, not that they disagreed)"
}
trades_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_trades)"; done; }
stp_all()    { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_stp_cancels)"; done; }
refs_all()   { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_next_order_ref)"; done; }
uniq_one()   { tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' '; }

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
replace() { # replace <orderRef> <qty> <price> -> "<http> <body>"
  local out
  out="$(curl -s --max-time 30 -o /tmp/yu13-gke-rep -w '%{http_code}' \
    -X POST "${MATCHER_URL}/replace" -H 'Content-Type: application/json' \
    -d "{\"orderRef\":$1,\"quantity\":$2,\"limitPrice\":$3}")"
  echo "${out} $(cat /tmp/yu13-gke-rep)"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
step "0. the divergence rule: every member on ${IMAGE##*:}, ready, and AGREEING before any traffic"
for i in $(seq 1 120); do
  state="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}' \
    | sort -u | tr -d '\n')"
  [[ "${state}" == "${IMAGE} true" ]] && break
  [[ ${i} -lt 120 ]] || fail "members never all reached ${IMAGE} and ready (saw: ${state})"
  sleep 5
done
echo "  all three members: ${IMAGE##*:}, ready"
RESTARTS0="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
start_pf
for acct in "${SELF}" "${OTHER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
BASE="$(digest_consensus)"
echo "  members agreed BEFORE traffic: [${BASE}]   (ticker ${TICKER}, accounts ${SELF}/${OTHER})"

# ---------------------------------------------------------------------------------------------
step "1. a replace is one order in and one order out, under the SAME orderRef"
RESTING="$(order "${OTHER}" Sell 5 "$(px 5)")"
REF_A="$(ref_of "${RESTING}")"
echo "  resting sell ref=${REF_A} qty=5 @ $(px 5) -> ${RESTING}"
BEFORE="$(digest_consensus)"
REP="$(replace "${REF_A}" 9 "$(px 3)")"
echo "  POST /replace qty 5->9, px $(px 5)->$(px 3)  ->  ${REP}"
[[ "${REP}" == 200* ]] || fail "replace not accepted: ${REP}"
[[ "${REP}" == *"\"orderRef\":${REF_A}"* ]] || fail "replace minted a new orderRef; identity was not preserved"
AFTER="$(digest_consensus)"
echo "  book: [${BEFORE}] -> [${AFTER}]"
[[ "${BEFORE}" != "${AFTER}" ]] || fail "the replace changed nothing on the members"
[[ "${BEFORE%% *}" == "${AFTER%% *}" ]] \
  || fail "depth moved: a replace must be one order in and one order out, not two orders"

# ---------------------------------------------------------------------------------------------
step "2. ONE input, cancel-plus-add: the caller gets the REPLACE's outcome, not the STP cancel"
# The participant's own quote, and their own far-away bid. Replacing the bid up to the quote makes
# it marketable into itself: inside ONE apply the replace commits (cancel-and-add) and cancel-oldest
# STP fires on the OTHER order. Two order-update acks leave for one input; only one of them is this
# session's direct response, and the gateway must not answer with the wrong one.
OWN_QUOTE="$(order "${SELF}" Sell 4 "$(px 0)")"; REF_Q="$(ref_of "${OWN_QUOTE}")"
OWN_BID="$(order "${SELF}" Buy 4 "$(px -5)")";   REF_B="$(ref_of "${OWN_BID}")"
echo "  ${SELF} own quote  ref=${REF_Q} sell 4 @ $(px 0)"
echo "  ${SELF} own bid    ref=${REF_B} buy  4 @ $(px -5)"
BEFORE="$(digest_consensus)"
STP0="$(stp_all)"; TR0="$(trades_all)"
REP="$(replace "${REF_B}" 4 "$(px 0)")"
echo "  POST /replace (bid $(px -5) -> $(px 0), into its own quote)  ->  ${REP}"
[[ "${REP}" == 200* ]] || fail "replace not accepted: ${REP}"
[[ "${REP}" == *"\"replaced\":true"* ]] || fail "the caller was answered with something other than the replace"
[[ "${REP}" == *"\"orderRef\":${REF_B}"* ]] \
  || fail "ACK CORRELATION BROKEN: the caller got an ack for ref $(ref_of "${REP}"), not the replaced ${REF_B}"
AFTER="$(digest_consensus)"
STP1="$(stp_all)"; TR1="$(trades_all)"
echo "  book: [${BEFORE}] -> [${AFTER}]"
echo "  stp_cancels:  [${STP0}] -> [${STP1}]"
echo "  engine trades:[${TR0}] -> [${TR1}]   (a self-trade must book nothing)"
[[ "$(( ${BEFORE%% *} - 1 ))" -eq "${AFTER%% *}" ]] \
  || fail "expected exactly one order to leave the book (the STP-cancelled quote)"
for m in 0 1 2; do
  s0=$(echo "${STP0}" | cut -d' ' -f$((m+1))); s1=$(echo "${STP1}" | cut -d' ' -f$((m+1)))
  [[ "${s1}" -eq "$(( s0 + 1 ))" ]] || fail "member ${m}: expected exactly 1 STP cancel, saw $(( s1 - s0 ))"
  t0=$(echo "${TR0}" | cut -d' ' -f$((m+1))); t1=$(echo "${TR1}" | cut -d' ' -f$((m+1)))
  [[ "${t1}" -eq "${t0}" ]] || fail "member ${m} booked a self-trade"
done
echo "  the replaced order survived and its own quote was cancelled — never the other way round"

# ---------------------------------------------------------------------------------------------
step "3. three-member state identity through the replace sequence"
[[ "$(refs_all | uniq_one)" == "1" ]]  || fail "members disagree on nextOrderRef: [$(refs_all)]"
[[ "$(trades_all | uniq_one)" == "1" ]] || fail "members disagree on the trade counter: [$(trades_all)]"
[[ "$(stp_all | uniq_one)" == "1" ]]    || fail "members disagree on the STP counter: [$(stp_all)]"
echo "  nextOrderRef [$(refs_all)]  trades [$(trades_all)]  stp [$(stp_all)]  book [$(digest_consensus)]"

# ---------------------------------------------------------------------------------------------
step "4. a replace survives a snapshot AND a member rebuilt from nothing"
SNAP0="$(member_metric 0 traderx_cluster_snapshots)"
echo "  waiting for a snapshot to be taken after the replace (interval 300s)…"
for i in $(seq 1 80); do
  [[ "$(member_metric 0 traderx_cluster_snapshots)" -gt "${SNAP0}" ]] && break
  [[ ${i} -lt 80 ]] || fail "no snapshot was taken within the wait window"
  sleep 10
done
echo "  snapshots: ${SNAP0} -> $(member_metric 0 traderx_cluster_snapshots)"
# ...and MORE replaces after the snapshot, so the recovering member must combine snapshot + tail.
TAIL="$(order "${OTHER}" Sell 3 "$(px 8)")"; REF_T="$(ref_of "${TAIL}")"
replace "${REF_T}" 7 "$(px 6)" >/dev/null
echo "  post-snapshot: rested ref=${REF_T} and replaced it to qty 7 @ $(px 6)"
PRE_KILL="$(digest_consensus)"

VICTIM="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{end}' | tr ' ' '\n' | grep . | tail -1)"
echo "  DESTROYING ${VICTIM} — emptyDir, so it comes back with an EMPTY disk and rebuilds from"
echo "  the snapshot plus the log tail. This is the restore boundary."
${K} delete pod "${VICTIM}" --wait=true >/dev/null
# `kubectl wait --for=condition=Ready` does NOT wait for a pod to be CREATED. Against a name that
# does not exist it returns `NotFound` IMMEDIATELY, and the --timeout never applies at all. The line
# above just deleted the pod, so there is ALWAYS a window before the controller recreates it, and
# how wide that window is depends on how busy the box is -- so this passes until it does not, then
# reports "never became Ready" about a pod that had not yet been asked to exist. Caught in
# yu17-swap-netting on 2026-08-14; the same shape is here. Wait for EXISTENCE first.
for _ in $(seq 1 150); do
  ${K} get pod "${VICTIM}" >/dev/null 2>&1 && break
  sleep 2
done
${K} wait --for=condition=Ready "pod/${VICTIM}" --timeout=600s >/dev/null
POST_KILL="$(digest_consensus)"
echo "  book: [${PRE_KILL}] -> [${POST_KILL}] after the rebuild"
[[ "${PRE_KILL}" == "${POST_KILL}" ]] \
  || fail "the rebuilt member did not converge to the pre-kill book"

# The falsifiable part: trade the restored order AT ITS REPLACED price and quantity. If the replace
# had not survived the snapshot/restore, ref_A would still be qty 5 @ PRICE+5 and this buy would not
# cross it at all.
TR0="$(trades_all)"
CLOSE="$(order "${SELF}" Buy 9 "$(px 3)")"
echo "  buy 9 @ $(px 3) (the REPLACED size and price of ref ${REF_A}) -> ${CLOSE}"
sleep 3
TR1="$(trades_all)"
echo "  engine trades: [${TR0}] -> [${TR1}]"
for m in 0 1 2; do
  t0=$(echo "${TR0}" | cut -d' ' -f$((m+1))); t1=$(echo "${TR1}" | cut -d' ' -f$((m+1)))
  [[ "${t1}" -eq "$(( t0 + 2 ))" ]] \
    || fail "member ${m}: the restored order did not fill at its replaced price/size — the replace did not survive"
done
[[ "$(digest_consensus)" ]] >/dev/null
echo "  the restored order filled at qty 9 @ $(px 3): the replace survived snapshot + rebuild"

# ---------------------------------------------------------------------------------------------
step "5. a REJECTED replace leaves the order exactly as it was"
LIVE="$(order "${OTHER}" Sell 6 "$(px 4)")"; REF_R="$(ref_of "${LIVE}")"
BEFORE="$(digest_consensus)"
REJ="$(replace "${REF_R}" 6 "$(px 5000)")"   # far outside the price band
echo "  POST /replace to an out-of-band price -> ${REJ}"
[[ "${REJ}" == 422* ]] || fail "expected 422 for a rejected replace, got ${REJ}"
[[ "${REJ}" == *PRICE_COLLAR* ]] || fail "expected reason PRICE_COLLAR in the body: ${REJ}"
AFTER="$(digest_consensus)"
[[ "${BEFORE}" == "${AFTER}" ]] \
  || fail "a REJECTED replace changed the book — the client's order was not left intact"
TR0="$(trades_all)"
order "${SELF}" Buy 6 "$(px 4)" >/dev/null
sleep 3
TR1="$(trades_all)"
for m in 0 1 2; do
  t0=$(echo "${TR0}" | cut -d' ' -f$((m+1))); t1=$(echo "${TR1}" | cut -d' ' -f$((m+1)))
  [[ "${t1}" -eq "$(( t0 + 2 ))" ]] \
    || fail "member ${m}: the order that survived the rejected replace could not be traded"
done
echo "  the order survived untouched and still trades at its original price"

step "6. no member bounced except the one this proof destroyed"
RESTARTS1="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
echo "  restart counts: [${RESTARTS0}] -> [${RESTARTS1}]  (the destroyed pod is a NEW pod, count 0)"

echo
echo "[PASS] atomic replace on a real cluster: ack correlation under cancel-plus-add, three-member"
echo "       identity, and survival across a snapshot and a member rebuilt from an empty disk."
echo "       Not asserted here: the SQL read model. There is no order read model at all, and the"
echo "       trades read model is deliberately not deployed for this proof — booked trades are"
echo "       asserted on the engine's per-member counter, which is authoritative."
