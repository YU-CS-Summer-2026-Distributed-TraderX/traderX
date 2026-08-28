#!/usr/bin/env bash
# yu13-gke-replace-proof.sh — the three things atomic replace (ADR-058) still needed proving on a
# real cluster, run on GKE because kind cannot carry them.
#
# ============================================================================================
# ADR-072 EXPOSURE: ALL NINE ASSERTIONS FIXED IN ONE CHANGE (2026-08-27). Read this before you
# add an assertion to this file.
#
# Since ADR-072 the rig has a THIRD writer, replaying sampled TAQ prints as real orders at ~6/s.
# Nine assertions here claimed an exact delta, or an unchanged absolute, across a window on
# counters every writer moves. Eight would have failed in the ACCUSATORY direction — naming the
# replace, the STP, or a rebuilt member for something the tape did. The ninth could not fail.
#
# THIS HEADER NAMES STEPS, NOT LINE NUMBERS, DELIBERATELY. The previous two versions of it were
# wrong within a day because a header insertion renumbered the file underneath them, and a stale
# line number sends the next reader to the wrong assertion. Steps do not shift.
#
# WHAT REPLACED THEM, in lib-consensus-readings.sh's preference order:
#
#   ASK THE ORDER (five sites). Each count was standing in for "THIS order did X", which the
#   order's own read-model row states directly — status, quantity, limit price — and which no
#   other writer can move. Strictly STRONGER than the count it replaced: "depth fell by one" is
#   satisfied by any order leaving; "ref Q reads CANCELED and ref B still rests at the new price"
#   says WHICH. Identity is what a proof named for replace and ack-correlation exists to
#   establish, so the count was always the proxy.
#     step 1  one order in, one out   -> ref A rests NEW at the REPLACED qty and price, AND the
#                                        account's open set on this ticker is exactly {A}. That
#                                        second half is the "not two orders" claim the depth
#                                        equality was making, scoped to a ticker the tape cannot
#                                        touch.
#     step 2  the quote left the book -> ref Q reads CANCELED and ref B still reads NEW. The old
#                                        depth-minus-one could not tell which of them went.
#     step 4  the rebuilt member      -> DELETED, not replaced. digest_consensus already asserts
#             converged                  three-member agreement and now includes the member
#                                        rebuilt from an empty disk; that IS the convergence
#                                        claim. The equality across the kill added nothing except
#                                        a requirement that the venue be quiet for the seconds a
#                                        member takes to rejoin — which is the one window where
#                                        replayed flow is guaranteed to be moving it.
#     step 4  the replaces survived   -> refs A and T read NEW at their replaced terms AFTER the
#             snapshot + rebuild         rebuild. Says far more than a digest byte-compare did.
#     step 5  a REJECTED replace left -> ref R still reads NEW at its ORIGINAL qty and price.
#             the order alone
#
#   THE OPERATOR TWIN, per member (two sites), where the claim really is about volume:
#     step 2  exactly one STP cancel     traderx_stp_operator_cancels     (twin landed 6374c110)
#     step 2  the self-cross booked 0    traderx_cluster_operator_trades
#
#   assert_order_effects (two sites). The two "+2 legs" checks are volume claims that also have
#   to be ATTRIBUTABLE, so the operator TRADE delta is bracketed by the operator REF delta:
#   "exactly my one order was sequenced in this window, and it had exactly this trade effect".
#   Either half alone is the vacuous form — see the library.
#
#   DELETED (one site, step 1). `BEFORE != AFTER` on the digest — "the replace changed nothing on
#   the members". Replayed flow rewrites the digest continuously, so it held whether or not the
#   replace did anything: zero coverage that read as coverage, and the only one of the nine that
#   would never have printed a red to tell anyone. The orderRef identity above it is the real
#   claim. Same shape deleted from yu13-cancel-ingress.
#
# WHY THERE IS NO traderx_book_operator_open_orders, decided and not to be relitigated silently:
# it is a GAUGE over resting state, so there is no monotonic external contribution to subtract. A
# real twin would need resting orders tracked by account range — engine state added for an
# observability artifact — and by the persistence rule its shadow would have to be snapshotted
# like the gauge, i.e. a format bump and a mandatory fresh epoch. Every one of its call sites
# wanted an identity claim anyway, and now makes one.
#
# THE PERSISTENCE RULE, because it is what decides whether a twin survives step 4's rebuild:
#   traderx_cluster_operator_trades  SNAPSHOTTED on BOTH halves (tradeCounter, and the engine's
#       externalTradeLegs at snapshot offset 60 — MatchingEngineClusteredService:1500). So it
#       survives the restore and can still be quiesced across all three members afterwards. That
#       is what makes step 4's use of it legal; a per-process twin there would have substituted a
#       fresh bug for the one being removed.
#   traderx_stp_operator_cancels     PER-PROCESS on both halves (selfTradesPrevented is a plain
#       field, in neither the snapshot writer nor the reader). Its per-member DELTA is sound; its
#       cross-member ABSOLUTE is not, after any restart. Used only in step 2, before the kill.
#   The four are NOT uniform. Check the PARENT before assuming either — ClusterNodeMain's twin
#   block spells out which is which and what each one cost.
#
# THE READ MODEL IS NOW A HARD DEPENDENCY, and the trailer that used to deny it was stale: the
# GKE bring-up deploys trade-processor and waits on its rollout, and the YU17 gke layer carries
# trade-processor.yaml. Step 0 REFUSES TO RUN if it cannot read — a reader that exists is not a
# reader that answers, and a probe silently returning "" would turn every identity claim above
# into a green that cannot fail, which is precisely the defect this change removes. The forward
# claims (NEW in step 1, CANCELED in step 2) run on every pass, so they are also the standing
# positive control on the reader itself.
#
# THIS PROOF DESTROYS A MEMBER, AND NOW SAYS SO BEFORE IT DOES. `DESTRUCTIVE=1` is required; the
# default refuses and exits 2 without touching the cluster. Step 4 deletes a cluster member and
# rebuilds it from an empty disk -- on a SHARED rig that is somebody else's state. The two comparable
# proofs (yu17-halt-survives-failover, yu17-closed-survives-restart) have been gated since they were
# written; this one never was, which is also why it must not enter run-proofs.sh ungated.
#
# SELFTEST=1 RUNS THE READ-MODEL PARSERS OFFLINE, IN ABOUT A SECOND, WITH NO CLUSTER:
#
#     SELFTEST=1 ./yu13-gke-replace-proof.sh
#
# Those parsers are the instrument five assertions now depend on, and a bug in one does NOT read as
# a parser bug -- it reads as "the order never became visible", i.e. a verdict about the replace.
# Run it before you spend a rig on a shape bug, and after ANY edit to row()/open_on_ticker(). It
# covers the dash-anchoring that must not read ref '4' out of order id '1-504', numeric rather than
# lexicographic ref sort, and empty/non-JSON responses parsing as "cannot read" rather than "absent".
#
# THE TAPE MUST BE LIVE. Step 0 fails if price-publisher reports the replay off or absent, and
# step 6 requires it to have submitted orders ACROSS this run. A green with the tape stopped
# proves nothing about the class these assertions were rewritten for, and this file has never
# once run under live replay — so it gets to prove the tape was writing while it passed.
#
# THE TENTH, FOUND BY RUNNING IT (2026-08-27) AFTER ALL NINE WERE FIXED — AND IT IS A DIFFERENT
# SHAPE, WHICH IS WHY THREE ENUMERATIONS AND TWO PEOPLE ALL CLASSIFIED IT "SOUND":
#
#   Step 3's three `uniq_one` cross-member checks. The CLAIM is sound and always was — "the members
#   agree" cannot be disturbed by who wrote the orders. The MEASUREMENT was not. refs_all/
#   trades_all/stp_all read a global counter with three SEQUENTIAL `kubectl exec`s, and the tape
#   advances it between them, so a member that sampled earlier reads lower and is reported as
#   disagreeing. Measured at 6.13/s: `refs [21580 21586 21586]`, `trades [18006 18006 18010]`,
#   **8 skews in 80 samples**. ~90% pass rate — the worst kind, because the red gets re-run.
#
#   The lesson the first nine did NOT teach: **classifying the assertion is not enough — ask how it
#   SAMPLES.** "Cross-member agreement is safe" is a statement about the claim; a claim you cannot
#   sample coherently is still unmeasurable. Fixed with agree_on(), the retry digest_consensus has
#   always had, whose own comment was pointing straight at these three checks.
#
#   It also printed a self-contradicting failure — "members disagree: [20850 20850 20850]" — because
#   the assertion and the message called the reader twice and got different samples. Same disease as
#   `d26d9851`: a success or failure line narrating something other than what was asserted.
#
# STILL SOUND, AND LEFT ALONE IN SUBSTANCE: the `uniq_one` cross-member agreement checks in step 3. "The members
# agree" is true no matter who wrote the orders. They read the GLOBAL counters deliberately: the
# twins are the wrong instrument for a determinism check, and the STP twin is per-process, so a
# cross-member absolute on it could not survive a restart anyway. The old header warned that
# trades_all() fed BOTH those and the exposed per-member deltas — one helper, sound in one line
# and exposed in the next — so the helpers are now split: trades_all()/stp_all() serve only the
# agreement checks, op_trades_all()/op_stp_all() only the deltas.
#
# HOW THE ENUMERATION WAS GOT WRONG, TWICE, WHICH IS THE USEFUL PART:
#   * SIX, from enumerating readers of traderx_book_open_orders. A search by METRIC NAME cannot
#     see a site exposed through a different counter.
#   * SEVEN, when one turned up in the same `for m in 0 1 2` loop as another, two lines below,
#     and the loop had been read as ONE site. A loop is N assertions.
#   * NINE, by enumerating what the file ASSERTS (`grep -nE '^\s*\[\[|^\s*\(\('`) and classifying
#     each. Two compare a whole digest string and appear in no metric-name search at all.
#   Enumerate what the file ASSERTS, not what it READS. If you add an assertion here, classify it
#   in this header at the same time.
#
# See scripts/proofs/lib-consensus-readings.sh (the "SHAPE OF THE READING" block) and
# issues/open/five-gke-proofs-read-a-global-counter-that-replayed-flow-now-moves.md.
# ============================================================================================
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
#   2. Three-member state identity through a replace — the book digest (depth AND order hash)
#      agreed by all three at every digest_consensus call, and the reference generator and trade
#      counter agreed at step 3. NOT "every counter at every step": the STP counter is
#      deliberately not checked for agreement at all, because it is per-process and agreement on
#      it would be a statement about uptime (see step 3).
#   3. A replace surviving a snapshot/restore boundary mid-sequence — snapshot taken AFTER the
#      replace, more replaces applied after it, then a member destroyed and rebuilt from nothing
#      (emptyDir: the pod comes back with an empty disk and rejoins from snapshot + log). The
#      restored order is then traded AT ITS REPLACED PRICE AND QUANTITY, which is what makes the
#      assertion falsifiable: if the replace had not survived, the closing order would not cross.
#
# The divergence rule is applied on the way in — every member running the target image AND ready,
# then the members agreed, BEFORE any traffic. That rule was earned on this exact change: a
# rolling window is a divergence window while members compute different functions of the same log.
set -euo pipefail

CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu17-gke6}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18210}"
SELF="${SELF:-42422}"
OTHER="${OTHER:-22214}"
# A MINTED ticker, and the library's rule depends on it: the tape never trades this symbol, so
# nothing replayed can cross our orders and the operator trade deltas below are exactly ours.
TICKER="${TICKER:-RPL$(date +%H%M%S)}"
PRICE="${PRICE:-150.00}"
# ADR-072 decision: "sample to a target order rate -- order 5-20/sec, tunable". Overridable because
# the ADR calls it tunable, but NOT to be widened to make a run pass: see the rate gate in step 0.
REPLAY_MIN_RATE="${REPLAY_MIN_RATE:-5}"
REPLAY_MAX_RATE="${REPLAY_MAX_RATE:-20}"
# THIS PROOF DESTROYS A CLUSTER MEMBER (step 4) AND HAD NO GATE. Same shape as
# yu17-halt-survives-failover and yu17-closed-survives-restart, which have carried one since they
# were written -- this file simply never grew one, and on a SHARED rig that means an unsuspecting
# run kills somebody else's member silently. It was run twice that way on 2026-08-27 before anyone
# noticed the asymmetry.
DESTRUCTIVE="${DESTRUCTIVE:-0}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
px() { python3 -c "print(${PRICE} + $1)"; }

here="$(cd "$(dirname "$0")" && pwd)"; . "${here}/lib-consensus-readings.sh"
# pressure_row/print_pressure/operator_expectation were written HERE and generalised into this
# library by the lane fixing the other four GKE proofs. Sourcing it rather than keeping the private
# copies: two definitions of one name in one directory is how a fix lands on the inert one.
. "${here}/lib-gke-replay-gates.sh"

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
# THE GLOBAL counters, for the CROSS-MEMBER AGREEMENT checks in step 3 and nothing else. "The
# members agree" is a claim about determinism that a third writer cannot touch, and the raw engine
# counters are the right instrument for it.
trades_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_trades)"; done; }
stp_all()    { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_stp_cancels)"; done; }
refs_all()   { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_next_order_ref)"; done; }
# THE OPERATOR TWINS, for the per-member DELTAS and nothing else. Split from the two above on
# purpose: the same helper feeding a sound agreement check and an exposed delta is how this file
# came to have nine exposed assertions that a metric-name search kept missing.
op_trades_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_operator_trades)"; done; }
op_stp_all()    { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_stp_operator_cancels)"; done; }
uniq_one()   { tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' '; }

# RETRIED, for exactly the reason digest_consensus is -- and the omission took a live tape to find.
# refs_all/trades_all/stp_all read a GLOBAL counter with three SEQUENTIAL `kubectl exec`s, and
# ADR-072's replay advances all three counters BETWEEN those reads. A member reading lower is then
# not a member that disagrees, it is a sample taken earlier. Measured on kind 2026-08-27 with the
# tape at 6.13/s:
#
#     refs   [21580 21586 21586]      trades [18006 18006 18010]
#     8 skews in 80 samples -- it passes ~90% of the time
#
# which is the worst rate there is: the red gets re-run and goes green. THE CLAIM IS STILL SOUND --
# who wrote the orders is irrelevant to whether the members agree -- but the MEASUREMENT was not,
# and no amount of reasoning about the claim could have found that. Only running it under a live
# tape did. This file already knew the answer and had applied it in one place only: the comment in
# digest_consensus says a single sample "looks exactly like a determinism failure and is not one".
# The three checks below were the ones it was pointing AT, and they never got the retry.
#
# Returning the agreed reading also removes a second defect: step 3 used to call each reader TWICE,
# once for the assertion and once for the failure message, so the message printed a DIFFERENT and
# usually coherent sample -- "members disagree: [20850 20850 20850]", a claim contradicted by the
# data printed inside it. Assert and report the same reading.
agree_on() { # agree_on <reader-fn> <what-it-is> -> the agreed reading
  local v i
  for i in $(seq 1 40); do
    v="$("$1")"
    [[ "$(printf '%s' "${v}" | uniq_one)" == "1" ]] && { printf '%s' "${v}"; return 0; }
    sleep 1
  done
  fail "the members never agreed on ${2}: [${v}]
  Retried 40x, so this is a PERSISTENT split and not the sampling skew the retry exists for. On a
  deterministic core that is the most serious thing this proof can report."
}

# --- the read model: ASK THE ORDER, do not count the venue ------------------------------------
# Written from the LEADER's egress, so a status here is a COMMITTED apply and not the gateway's
# opinion of one. This is the effect end that replaced five venue-wide counter assertions.
tp() { # tp <account> [all] -> the raw JSON array of that account's orders
  ${K} exec deploy/trade-processor -- \
    wget -qO- "http://localhost:18091/accounts/$1/orders${2:+?status=all}" 2>/dev/null || true
}
row() { # row <account> <ref> -> "<status> <quantity> <limitPrice>", or "" if not visible yet
  tp "$1" all | python3 -c "
import sys, json
want = '$2'
try:
    rows = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
for r in rows:
    if str(r.get('id', '')).rsplit('-', 1)[-1] == want:
        print(r.get('status', ''), r.get('quantity', ''), float(r.get('limitPrice') or 0)); sys.exit(0)
print('')" 2>/dev/null || true
}
open_on_ticker() { # open_on_ticker <account> -> that account's OPEN refs on ${TICKER}, ascending
  # No ?status=all: the default route returns only NEW/PARTIALLY_FILLED/QUEUED, which is the open
  # set. Scoped to our MINTED ticker, so this is a statement no other writer can reach.
  tp "$1" | python3 -c "
import sys, json
try:
    rows = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
refs = [str(r.get('id', '')).rsplit('-', 1)[-1] for r in rows if r.get('security') == '${TICKER}']
print(' '.join(sorted((r for r in refs if r.isdigit()), key=int)))" 2>/dev/null || true
}
await_status() { # await_status <account> <ref> <want-status> <what> -> echoes the whole row
  local got=""
  for _ in $(seq 1 45); do
    got="$(row "$1" "$2")"
    [[ "${got%% *}" == "$3" ]] && { printf '%s' "${got}"; return 0; }
    sleep 2
  done
  [[ -n "${got}" ]] \
    || fail "$4: order $2 never became visible in the read model at all. That is the PROBE failing
  to read — NOT a verdict about the replace. Check trade-processor and its NATS feed."
  fail "$4: order $2 reads '${got%% *}', wanted '$3'   (row: ${got})"
}
# Numeric, because the read model returns limitprice as 153.000 where px() prints 153.0.
same_num() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a+0==b+0)}'; }
assert_terms() { # assert_terms <row> <qty> <price> <what>
  local q p
  q="$(awk '{print $2}' <<<"$1")"; p="$(awk '{print $3}' <<<"$1")"
  same_num "${q}" "$2" || fail "$4: quantity reads ${q}, expected $2   (row: $1)"
  same_num "${p}" "$3" || fail "$4: limit price reads ${p}, expected $3   (row: $1)"
}

# SELF-TEST THE PROBE BEFORE ARMING IT. `SELFTEST=1 ./yu13-gke-replace-proof.sh` exercises the three
# read-model parsers against fixture JSON, with no cluster and no rig time. They are the instrument
# five assertions now depend on, and a parser bug in them does not read as a parser bug: it reads as
# "the order never became visible", i.e. a verdict about the replace. Cheap to run, so run it before
# you spend a metered rig on a shape bug.
if [[ "${SELFTEST:-0}" == "1" ]]; then
  TICKER=ACME
  tp() { # the real route filters by status server-side; this fixture deliberately does not, so the
         # security filter and the numeric sort below are actually exercised rather than assumed.
    [[ "$1" == "empty" ]] && { echo "[]"; return 0; }
    [[ "$1" == "broken" ]] && { echo "<html>502</html>"; return 0; }
    cat <<'JSON'
[{"id":"ep7-101","accountId":22214,"security":"ACME","side":"Sell","quantity":9,"limitPrice":153.000,"status":"NEW"},
 {"id":"ep7-17","accountId":22214,"security":"ACME","side":"Buy","quantity":4,"limitPrice":150.000,"status":"CANCELED"},
 {"id":"ep7-2","accountId":22214,"security":"ZZZZ","side":"Buy","quantity":1,"limitPrice":9.500,"status":"NEW"}]
JSON
  }
  _t=0; _f=0
  chk() { _t=$((_t+1)); [[ "$2" == "$3" ]] || { echo "  [FAIL] $1: got '$2', want '$3'"; _f=$((_f+1)); }; }
  chk "row reads status+qty+price"  "$(row x 101)"           "NEW 9 153.0"
  chk "row reads a terminal state"  "$(row x 17)"            "CANCELED 4 150.0"
  chk "absent ref reads empty"      "$(row x 999)"           ""
  # '-7' must not match '-17'. A suffix compare that used endswith/LIKE would return the WRONG
  # order's status here -- the dash-anchoring bug yu13-readmodel-effect-end calls out in SQL.
  chk "no suffix bleed 7 vs 17"     "$(row x 7)"             ""
  # Numeric sort, not lexicographic: lexicographic gives "101 17" and would make the step-1 open-set
  # equality fail against a correct venue.
  chk "open set, ticker-scoped"     "$(open_on_ticker x)"    "17 101"
  chk "empty array is not a crash"  "$(row empty 101)"       ""
  chk "empty array open set"        "$(open_on_ticker empty)" ""
  # A 502 from the route must parse as "cannot read", never as "the order is absent" -- the step-0
  # gate is what turns this into a refusal to run, and this arm proves it does not throw first.
  chk "non-JSON is not a crash"     "$(row broken 101)"      ""
  same_num 153.000 153.0 || { echo "  [FAIL] same_num 153.000 == 153.0"; _f=$((_f+1)); }; _t=$((_t+1))
  same_num 153.0 154.0 && { echo "  [FAIL] same_num must reject 153.0 == 154.0"; _f=$((_f+1)); }; _t=$((_t+1))
  assert_terms "NEW 9 153.0" 9 153.000 "selftest" ; _t=$((_t+1))
  echo "selftest: $(( _t - _f ))/${_t} passed"
  [[ ${_f} -eq 0 ]] || exit 1
  exit 0
fi

# THE WRITE PRESSURE THIS RUN ACTUALLY RAN UNDER, so a green is qualified by it rather than merely
# claiming it. Six counters from ONE scrape -- deliberately a single HTTP call, so the readings are
# mutually coherent and this cannot reproduce the sequential-sampling skew that agree_on() exists to
# survive. Reporting only: the rate gate in step 6 is what ASSERTS the pressure was real.

# --- the tape, which must be writing while this proof passes -----------------------------------
pub()  { ${K} exec deploy/price-publisher -- wget -qO- "http://localhost:18100$1" 2>/dev/null || true; }
jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
for k in sys.argv[1].split('.'):
    if isinstance(d, dict) and k in d:
        d = d[k]
    else:
        sys.exit(4)
print('' if d is None else d)" "$1"; }

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
# REFUSE, rather than run a reduced version. Steps 1, 2, 3 and 5 do not need the kill, so a partial
# run is technically possible -- and it must not be the default, because the [PASS] banner claims
# "survival across a snapshot and a member rebuilt from an empty disk" and a run that skipped step 4
# has not shown that. EXIT 2, the sibling proofs' convention: a partial run must never read as the
# claim, and a distinct exit code keeps "refused" separable from "passed" and "failed" by a caller.
if [[ "${DESTRUCTIVE}" != "1" ]]; then
  cat >&2 <<'REFUSED'
[SKIP] DESTRUCTIVE=0 (the default). Step 4 of this proof DELETES a cluster member and rebuilds it
       from an empty disk, which on a shared rig destroys state somebody else may be using. Nothing
       has run and nothing was touched.

       The kill is not incidental -- it is how this proof shows a replace surviving the
       snapshot/restore boundary, which is one of the three things it exists to establish. Running
       the other steps alone would print a PASS for a claim that was never tested, so this refuses
       instead of reducing itself.

       When you have a rig to spend, and the tape live (it asserts that too):

         DESTRUCTIVE=1 CTX=<context> IMAGE=<the image all three members are on> \
           bash scripts/proofs/yu13-gke-replace-proof.sh

       SELFTEST=1 needs no cluster and no permission -- it exercises the read-model parsers offline.
REFUSED
  exit 2
fi

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

# THE READER MUST PROVE IT CAN READ, BEFORE ANY CLAIM DEPENDS ON IT. Five assertions below are the
# order's own state out of this read model. A reader that answered "" for every ref would satisfy
# nothing and fail loudly at the first await_status — but a reader that answered an EMPTY ARRAY for
# every account would be indistinguishable from "the order is not there yet" until the timeout, and
# would report it as a verdict about the replace. Refuse up front instead.
[[ "$(${K} get deploy trade-processor -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" == "1" ]] \
  || fail "trade-processor is not READY. This proof's identity claims are read from the order read
  model, so it cannot run without one. (The GKE bring-up deploys it; check the rollout.)"
PROBE="$(tp "${OTHER}" all)"
[[ "${PROBE}" == \[* ]] \
  || fail "GET /accounts/${OTHER}/orders?status=all did not answer with a JSON array (got:
  ${PROBE:-nothing}). A reader that EXISTS is not a reader that ANSWERS, and every identity claim
  in this proof would otherwise go green off a read that never worked."
echo "  order read model answers on trade-processor:18091 (the effect end for every claim below)"

# THE TAPE IS A WRITER, AND THIS PROOF IS ONLY MEANINGFUL WHILE IT IS RUNNING. Every assertion here
# was rewritten because replayed flow moved a counter underneath it; a run with the tape stopped
# re-proves none of that and would quietly restore confidence in the shapes that were removed.
PUB_H="$(pub /health)"
[[ -n "${PUB_H}" ]] || fail "price-publisher /health did not answer — cannot establish the tape is live"
SUBMITTED0="$(printf '%s' "${PUB_H}" | jget printReplay.submitted)" \
  || fail "price-publisher /health carries NO printReplay block: this build predates ADR-072. This
  proof asserts it passed WHILE the tape was writing, and on this rig it cannot."
REPLAY_ERR="$(printf '%s' "${PUB_H}" | jget printReplay.error)" || REPLAY_ERR=""
[[ -z "${REPLAY_ERR}" ]] || fail "the replay is OFF: ${REPLAY_ERR}
  A green here with the tape stopped proves nothing about the class this file was rewritten for."
RATE0="$(printf '%s' "${PUB_H}" | jget printReplay.ordersPerSecond)" || RATE0=""
echo "  tape live: $(printf '%s' "${PUB_H}" | jget printReplay.symbols) symbols at ~${RATE0}/s, ${SUBMITTED0} orders submitted so far"
# ENABLED IS NOT THE SAME AS WRITING AT PRESSURE. A rig shipped 2026-08-27 reported enabled with
# error:null and plausible orders while replaying at 1.53/s -- a quarter of target, because the
# print-sample Secret and PRICE_TICKERS disagreed, and NOTHING reported the disagreement. Every
# arm below would still have gone green, and the "submitted climbed" check in step 6 passes at any
# rate above zero. So the band is asserted, not printed. Do not widen it to make a run pass: a
# quiet tape is the condition under which the assertions this file used to make ALSO passed.
awk -v r="${RATE0}" -v lo="${REPLAY_MIN_RATE}" -v hi="${REPLAY_MAX_RATE}" \
  'BEGIN{exit !(r+0 >= lo+0 && r+0 <= hi+0)}' \
  || fail "the tape reports ${REPLAY_MIN_RATE:+}${RATE0:-no}/s, outside ADR-072's ${REPLAY_MIN_RATE}-${REPLAY_MAX_RATE}/s band.
  Enabled and error-free is NOT the same as writing at pressure, and a quarter-rate tape lets every
  assertion here pass for the wrong reason. Check the print-sample Secret against PRICE_TICKERS."
RUN_T0=${SECONDS}
PRESSURE0="$(pressure_row)"

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
echo "  book: [${BEFORE}] -> [${AFTER}]   (venue-wide; the tape moves this independently of us)"
# ASK THE ORDER. This used to assert `BEFORE != AFTER` (the digest changed, so the replace did
# something) and `BEFORE depth == AFTER depth` (a replace is not two orders). The first CANNOT FAIL
# under a tape that rewrites the digest at ~6/s, so it was deleted rather than repaired. The second
# was an identity claim wearing a counter's clothes, and the order states it directly: ref A is
# still resting, now at the REPLACED terms, and it is the ONLY thing this account has open on this
# ticker — which is what "one order in, one order out, not two" actually means.
ROW_A="$(await_status "${OTHER}" "${REF_A}" NEW "the replaced order must still be resting")"
assert_terms "${ROW_A}" 9 "$(px 3)" "the replace did not take effect on the resting order"
OPEN_A="$(open_on_ticker "${OTHER}")"
[[ "${OPEN_A}" == "${REF_A}" ]] \
  || fail "after the replace, ${OTHER} has open orders [${OPEN_A}] on ${TICKER}, expected exactly
  [${REF_A}]. A replace must be one order in and one order out — not two orders."
echo "  ref ${REF_A} reads NEW at qty 9 @ $(px 3), and is the only order ${OTHER} has open on ${TICKER}"

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
# The twins, per member. traderx_stp_cancels and traderx_cluster_trades are global over order
# writers, so the tape's own self-crosses and fills land in them; these two exclude replayed flow
# at the writer.
#
# DO NOT MOVE THE STP ASSERTION BELOW PAST A MEMBER REBUILD. traderx_stp_operator_cancels is
# PER-PROCESS on both halves, so a rebuilt member restarts it at 0. That breaks two different ways
# and only one of them is obvious: a cross-member ABSOLUTE cannot agree after a restart, and --
# less obvious -- a per-member DELTA that STRADDLES the restart subtracts a pre-restart s0 from a
# post-restart s1 and goes negative. It is safe HERE only because both reads happen in step 2,
# before step 4's kill; nothing structural protects it. The trade twin has no such constraint: it
# is snapshotted on both halves, which is why step 4 may use it across the rebuild.
STP0="$(op_stp_all)"; TR0="$(op_trades_all)"
REP="$(replace "${REF_B}" 4 "$(px 0)")"
echo "  POST /replace (bid $(px -5) -> $(px 0), into its own quote)  ->  ${REP}"
[[ "${REP}" == 200* ]] || fail "replace not accepted: ${REP}"
[[ "${REP}" == *"\"replaced\":true"* ]] || fail "the caller was answered with something other than the replace"
[[ "${REP}" == *"\"orderRef\":${REF_B}"* ]] \
  || fail "ACK CORRELATION BROKEN: the caller got an ack for ref $(ref_of "${REP}"), not the replaced ${REF_B}"
AFTER="$(digest_consensus)"
STP1="$(op_stp_all)"; TR1="$(op_trades_all)"
echo "  book: [${BEFORE}] -> [${AFTER}]   (venue-wide context, moved by the tape independently)"
echo "  operator stp_cancels:  [${STP0}] -> [${STP1}]"
echo "  operator trades:       [${TR0}] -> [${TR1}]   (a self-trade must book nothing)"
# ASK THE ORDER. This used to assert depth fell by exactly one — satisfied by ANY order leaving the
# venue, including one of the tape's. The claim is about WHICH order left: the participant's own
# quote was cancelled and the replaced bid survived. Both orders say so themselves.
ROW_Q="$(await_status "${SELF}" "${REF_Q}" CANCELED "self-trade prevention must cancel the resting quote")"
ROW_B="$(await_status "${SELF}" "${REF_B}" NEW "the REPLACED order must survive, never the other way round")"
assert_terms "${ROW_B}" 4 "$(px 0)" "the replaced bid is not at its new terms"
for m in 0 1 2; do
  s0=$(echo "${STP0}" | cut -d' ' -f$((m+1))); s1=$(echo "${STP1}" | cut -d' ' -f$((m+1)))
  [[ "${s1}" -eq "$(( s0 + 1 ))" ]] || fail "member ${m}: expected exactly 1 operator STP cancel, saw $(( s1 - s0 ))"
  t0=$(echo "${TR0}" | cut -d' ' -f$((m+1))); t1=$(echo "${TR1}" | cut -d' ' -f$((m+1)))
  [[ "${t1}" -eq "${t0}" ]] || fail "member ${m} booked a self-trade"
done
echo "  ref ${REF_Q} reads CANCELED and ref ${REF_B} still reads NEW at qty 4 @ $(px 0):"
echo "  the replaced order survived and its own quote was cancelled — never the other way round"

# ---------------------------------------------------------------------------------------------
step "3. three-member state identity through the replace sequence"
# Deliberately still on the GLOBAL counters: the claim is that the MEMBERS AGREE, and agreement on
# the global is the STRONGER determinism statement -- it covers the tape's own orders too, not just
# ours. What had to change is not the counter but the SAMPLING: see agree_on above, and the 8-in-80
# skew rate that exposed it. A single sequential sample of a moving global is not a reading.
REFS="$(agree_on refs_all  "nextOrderRef")"
TRD="$(agree_on trades_all "the trade counter")"
# NO AGREEMENT ASSERTION ON THE STP COUNTER, AND THIS IS NOT AN OVERSIGHT.
#
# traderx_stp_cancels is PER-PROCESS: selfTradesPrevented is a plain field on MatchingEngine, in
# neither the snapshot writer nor the reader. Cross-member equality on it is therefore a statement
# about how long each member has been UP, not about the state machine, and it is permanently
# unsatisfiable on any epoch where a member has restarted. Measured on kind 2026-08-27, minutes
# after this proof's own step 4 had rebuilt member 2:
#
#     traderx_cluster_next_order_ref   28358  28358  28358   replicated+snapshotted -> agrees
#     traderx_cluster_trades           23756  23756  23756   replicated+snapshotted -> agrees
#     traderx_stp_cancels               1372   1372    632   per-process -> CANNOT agree
#
# AND THIS PROOF CREATES THAT CONDITION ITSELF. Step 4 destroys and rebuilds a member, so a second
# run against the same epoch fails HERE -- "members disagree on the STP counter" -- accusing the
# cluster of divergence when nothing diverged and the restart being reported was this proof's own.
# The runs that passed did so only because step 3 precedes step 4 within a single run, which is not
# a property anyone chose.
#
# There is nothing to repair with a retry: unlike the skew above, the claim itself was never true of
# the system. The STP claim this proof actually needs is step 2's per-member DELTA on the operator
# twin, which is correct across exactly this case -- same rig, absolutes [2 2 0] -> [3 3 1]: three
# different starting points, +1 on every member.
#
# THE TWIN IS NOT IMMUNE, IT IS BEING USED CORRECTLY -- and the difference matters because the fix
# for the first nine WAS "swap the raw counter for its twin". traderx_stp_operator_cancels is a
# per-process shadow subtracted from a per-process parent, so it is per-process END TO END and its
# absolutes disagree exactly as its parent's do: measured alongside the readings above, [4 4 2].
# What makes step 2 sound is that it takes a DELTA, which is uptime-independent. A cross-member
# ABSOLUTE on the twin would be precisely as unsatisfiable as the assertion removed here. The twin
# fixes ATTRIBUTION, not PERSISTENCE, and nothing about the word "operator" implies otherwise.
#
# The pod ages are the mechanism in its cleanest form -- measured with the readings above:
# m0 37m, m1 37m, m2 18m. The counter was reporting how long each PROCESS had been alive, and this
# step was reading it as whether the STATE MACHINE agreed.
echo "  nextOrderRef [${REFS}] trades [${TRD}] book [$(digest_consensus)]"
echo "  stp [$(stp_all)] — per-process, so it differs across members by UPTIME. Not asserted:"
echo "       the STP claim is step 2's per-member delta on traderx_stp_operator_cancels."

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
# NAME THE CLUSTER IN THE IRREVERSIBLE LINE. CTX is a default that has been wrong before -- this
# file shipped for weeks pointing at a project deleted 2026-08-01 -- and a wrong-context kubectl
# answers truthfully about the wrong cluster. DESTRUCTIVE=1 says the operator accepted destroying a
# member; it does not say they accepted destroying THIS one.
echo "  cluster: ${CTX}   namespace: ${NS}"
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
# THE CONVERGENCE CLAIM IS digest_consensus ITSELF. It does not return until all three members —
# including the one just rebuilt from an empty disk — report a well-formed and IDENTICAL digest,
# retrying while the restored member catches up. That is the whole claim of this step.
#
# What used to be here as well was `PRE_KILL == POST_KILL`: the venue-wide digest unchanged ACROSS
# the kill and rebuild. Those are exactly the seconds in which replayed flow is guaranteed to be
# moving the book, so it would have gone red on a converged cluster and blamed the rebuilt member
# for not converging. Deleted, not swapped: the agreement above is stronger than the equality was,
# and the equality's only remaining content was "the venue was quiet", which is not a fact about
# this cluster and never was one this proof needed.
POST_KILL="$(digest_consensus)"
echo "  book: [${PRE_KILL}] -> [${POST_KILL}] after the rebuild"
echo "  all three members (including the rebuilt one) agree on the digest"
# ASK THE ORDERS. Both replaced orders must still be resting at their REPLACED terms — ref A from
# before the snapshot, ref T from after it, so the restored member had to combine snapshot AND log
# tail to hold both. This says far more than a digest byte-compare ever did.
ROW_A="$(await_status "${OTHER}" "${REF_A}" NEW "the pre-snapshot replace must survive the rebuild")"
assert_terms "${ROW_A}" 9 "$(px 3)" "ref ${REF_A} did not come back at its replaced terms"
ROW_T="$(await_status "${OTHER}" "${REF_T}" NEW "the post-snapshot replace must survive the rebuild")"
assert_terms "${ROW_T}" 7 "$(px 6)" "ref ${REF_T} did not come back at its replaced terms"
echo "  refs ${REF_A} (qty 9 @ $(px 3)) and ${REF_T} (qty 7 @ $(px 6)) both survived snapshot + rebuild"

# The falsifiable part: trade the restored order AT ITS REPLACED price and quantity. If the replace
# had not survived the snapshot/restore, ref_A would still be qty 5 @ PRICE+5 and this buy would not
# cross it at all.
#
# The trade delta is the OPERATOR twin, bracketed by the OPERATOR ref counter (assert_order_effects).
# Both halves are needed and the library is emphatic about why: the trade counter alone reads "+2"
# for anybody's cross, and the ref counter alone says an order was sequenced without saying what it
# did. The bracket is what makes "+2 legs" attributable to THIS order. Both counters are snapshotted
# on both halves, so they are still quiesceable across all three members after the rebuild above.
OREF0="$(quiesced_order_refs)"; OTRD0="$(quiesced_trades)"
CLOSE="$(order "${SELF}" Buy 9 "$(px 3)")"
echo "  buy 9 @ $(px 3) (the REPLACED size and price of ref ${REF_A}) -> ${CLOSE}"
sleep 3
OREF1="$(quiesced_order_refs)"; OTRD1="$(quiesced_trades)"
echo "  operator refs ${OREF0} -> ${OREF1}, operator trades ${OTRD0} -> ${OTRD1}"
assert_order_effects "${OREF0}" "${OREF1}" 1 "${OTRD0}" "${OTRD1}" 2 \
  "the restored order did not fill at its replaced price/size — the replace did not survive"
digest_consensus >/dev/null
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
echo "  book: [${BEFORE}] -> [${AFTER}]   (venue-wide context, moved by the tape independently)"
# ASK THE ORDER. This was the worst of the nine: it compared the FULL digest — depth AND the order
# hash — across the window, and the tape rewrites that hash on every book change. Measured on kind
# during the sibling fix: `524 <hash-a> -> 524 <hash-b>`, depth identical and hash different. Not
# fragile: UNSATISFIABLE. It would have failed on every future run and blamed the rejected replace
# for touching a book it never reached. The client's order says it is intact, at its ORIGINAL terms.
ROW_R="$(await_status "${OTHER}" "${REF_R}" NEW "a REJECTED replace must leave the client's order intact")"
assert_terms "${ROW_R}" 6 "$(px 4)" "the rejected replace altered the order it was refused for"
echo "  ref ${REF_R} still reads NEW at its ORIGINAL qty 6 @ $(px 4) — the rejection touched nothing"
OREF0="$(quiesced_order_refs)"; OTRD0="$(quiesced_trades)"
order "${SELF}" Buy 6 "$(px 4)" >/dev/null
sleep 3
OREF1="$(quiesced_order_refs)"; OTRD1="$(quiesced_trades)"
echo "  operator refs ${OREF0} -> ${OREF1}, operator trades ${OTRD0} -> ${OTRD1}"
assert_order_effects "${OREF0}" "${OREF1}" 1 "${OTRD0}" "${OTRD1}" 2 \
  "the order that survived the rejected replace could not be traded"
echo "  the order survived untouched and still trades at its original price"

step "6. no member bounced except the one this proof destroyed, and the tape wrote throughout"
RESTARTS1="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
echo "  restart counts: [${RESTARTS0}] -> [${RESTARTS1}]  (the destroyed pod is a NEW pod, count 0)"
# THE ANTI-VACUITY ARM FOR THE WHOLE FILE. Every assertion above was rewritten so that replayed
# order flow could not move it. That rewrite is only DEMONSTRATED by a run in which the tape was
# actually writing — otherwise this is a proof that the new shapes pass on a quiet venue, which is
# the one thing nobody doubted. Brackets the entire run, so it costs nothing.
SUBMITTED1="$(pub /health | jget printReplay.submitted)" || SUBMITTED1=""
[[ "${SUBMITTED1}" =~ ^[0-9]+$ ]] \
  || fail "could not read printReplay.submitted at the end of the run, so this proof cannot say the
  tape was live while it passed."
(( SUBMITTED1 > SUBMITTED0 )) \
  || fail "the tape submitted NO orders across this entire run (${SUBMITTED0} -> ${SUBMITTED1}).
  Every assertion above is designed to hold while a third writer moves the venue; with the tape
  stopped this run demonstrates none of that. Start the replay and run it again."
# THE OBSERVED RATE, MEASURED OVER THIS RUN. The step-0 gate reads a field the publisher reports
# about itself; this one counts what it actually did, over a window minutes long, and no
# misconfiguration can fake it. This is the reading that says the nine assertions were exercised
# under real foreign write pressure rather than merely with a tape switched on.
RUN_S=$(( SECONDS - RUN_T0 ))
OBS_RATE="$(awk -v n="$(( SUBMITTED1 - SUBMITTED0 ))" -v s="${RUN_S}" 'BEGIN{printf "%.2f", (s>0? n/s : 0)}')"
echo "  tape wrote throughout: submitted ${SUBMITTED0} -> ${SUBMITTED1} (+$(( SUBMITTED1 - SUBMITTED0 ))) over ${RUN_S}s = ${OBS_RATE}/s observed"
# THE GREEN, QUALIFIED BY THE PRESSURE IT RAN UNDER. Every global below is a counter this proof used
# to assert exact deltas on; every operator sibling is what it reads now. The globals show what the
# tape did to the venue during these assertions; the operator halves show our own work, and are the
# only numbers any assertion above touched. A green with the global deltas near zero is a green from
# a quiet venue and proves nothing about this class -- which is what the rate gate above refuses.
echo "  write pressure across this run (member 0, indented rows are the operator halves):"
print_pressure "${PRESSURE0}" "$(pressure_row)" 7
awk -v r="${OBS_RATE}" -v lo="${REPLAY_MIN_RATE}" -v hi="${REPLAY_MAX_RATE}" \
  'BEGIN{exit !(r+0 >= lo+0 && r+0 <= hi+0)}' \
  || fail "the tape averaged ${OBS_RATE}/s across this run, outside ADR-072's ${REPLAY_MIN_RATE}-${REPLAY_MAX_RATE}/s
  band. The venue was not under the write pressure these assertions were rewritten for, so this run
  does not demonstrate the class even though every arm above passed."

echo
echo "[PASS] atomic replace on a real cluster: ack correlation under cancel-plus-add, three-member"
echo "       identity, and survival across a snapshot and a member rebuilt from an empty disk —"
echo "       asserted while the ADR-072 tape replayed $(( SUBMITTED1 - SUBMITTED0 )) orders into the same venue."
echo "       The order-level claims are the ORDER'S OWN read-model state (status, quantity, price),"
echo "       which no other writer can move; the two volume claims are operator-scoped counters"
echo "       bracketed by the operator ref generator."
echo
echo "       PRECISELY: no assertion here ATTRIBUTES a venue-wide count to this proof's own work."
echo "       Three still READ venue-wide counters — the two step-3 agreement checks and every"
echo "       digest_consensus call — and that is sound, because the claim is that the MEMBERS"
echo "       AGREE, which no other writer can disturb. The earlier wording said \"no assertion"
echo "       reads a venue-wide count\", which was false and would have sent a reader either to"
echo "       fix step 3 for a defect it does not have, or to trust the line and miss it if"
echo "       somebody later added a read that does attribute."
echo
echo "       NOT SHOWN HERE: id separation across a WIPED epoch (this proof never mints one);"
echo "       the SQL trades read model; and any claim about behaviour under concurrent OPERATOR"
echo "       load — the scenario is sequential, and a second operator writing at the same time"
echo "       would move the operator counters the two volume claims bracket."
echo
echo "       BUT IT IS DETECTED, on the operator-ref line of the table above: this proof submits"
echo "       exactly SEVEN orders, so that row reading +7 means no other operator wrote. The four"
echo "       replaces consume NO ref — the ack preserves the orderRef, and the generator not"
echo "       advancing is the same fact at the engine level, which nothing here asserts."
