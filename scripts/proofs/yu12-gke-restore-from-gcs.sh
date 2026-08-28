#!/usr/bin/env bash
# yu12-gke-restore-from-gcs.sh — the DR proof: destroy the WHOLE cluster (all three members,
# emptyDir — state gone from every disk) → bring it back with RESTORE_FROM_GCS=1 → the book,
# positions, trade counter and reference generator are INTACT at exactly the last GCS backup —
# and the restored cluster takes new business.
#
# ============================================================================================
# ADR-072 EXPOSURE, FIXED 2026-08-27. Read this before adding an assertion to this file.
#
# The rig has a THIRD writer, replaying sampled TAQ prints as real orders at ~6/s. SIX assertions
# here were measured against counters that writer moves, and this proof had the worst of the five:
# its central claim was not merely contaminated, it became UNMEASURABLE. STEPS, NOT LINE NUMBERS.
#
#   step 4  `R == S` — "the restored state is EXACTLY the backup point". THE FLAGSHIP DEFECT, and
#           the only one in this family that NO RETRY CAN FIX. The restored cluster resumes taking
#           replayed order flow the instant it is up, so the venue-wide four-quantity state
#           diverges from the backup point monotonically from the first second. There is no
#           sampling window in which the equality holds; waiting makes it worse, not better.
#           Its else-branch then fails with "restored state matches neither the backup nor the
#           pre-wipe state — restore is corrupt", which is the single most damaging false
#           accusation in the whole class: a green DR path reported as data loss.
#
#           REPLACED BY THE OPERATOR TWINS, which do exactly what the global hash was doing here
#           and nothing it could not. Both are SNAPSHOTTED on both halves (externalOrderRefs at
#           snapshot offset 52, externalTradeLegs at 60 — MatchingEngineClusteredService:1499-1500,
#           read back at :1631), so they are restored FROM THE BACKUP along with everything else;
#           and replayed flow cannot move them by construction, so they do not drift while the
#           restored cluster comes up. That makes them a clean discriminator between the two
#           outcomes this step exists to tell apart:
#               restored == the values captured at backup time   -> restored from the tarball
#               restored == the values after the post-backup work -> the cluster SURVIVED
#           which is the restore-vs-survive distinction, stated on state this proof owns.
#
#   step 1  `S_CHECK == S` ("state moved during the backup — quiesce violated"). The backup Job
#           runs for minutes; the tape writes throughout. Guaranteed red, and it blames quiesce.
#           Now asserted on the operator twins: the venue is never quiet again, but OUR SLICE is,
#           and that is what the equality below actually needs.
#
#   step 2  `SPLUS != S` ("post-backup orders changed nothing"). An anti-vacuity guard that the
#           tape satisfies ~6 times a second — it held on a run where both post-backup orders had
#           been silently dropped, which is precisely the run it exists to catch. Now on the
#           operator ref counter, which only our orders move.
#
#   step 5  `T1 == T0 + 2` on traderx_cluster_trades — ACCUSATORY: any replayed fill inflates it
#           and the proof reports "the RESTORED resting order did not fill", blaming the restore.
#           Now assert_order_effects, so the trade delta is bracketed by the operator ref delta.
#
#   step 5  `POST_ref > S_ref` on the global generator — EXPOSED IN THE PASSING DIRECTION. The
#           tape advances it continuously, so "the reference generator continued past the restored
#           point" was true no matter what the restore did. Now on the operator counter.
#
# DO NOT REACH FOR THE READ MODEL HERE. It is fix #1 in lib-consensus-readings.sh and it is the
# WRONG INSTRUMENT for this proof specifically: trade-processor's database is NOT restored with the
# cluster, so after a restore it still holds the post-backup (S+) orders as open. An identity claim
# read from it would report the S+ orders present and call a correct restore a failure — the same
# false accusation, arrived at from the opposite direction. The engine's own snapshotted counters
# are the only readings that move with the restore. (The other three proofs in this family DO use
# the read model, correctly, because nothing in them wipes the cluster.)
#
# THE TAPE, AND WHY IT IS ASSERTED ASYMMETRICALLY HERE. The band is asserted across the PRE-DESTROY
# window, which is where every rewritten assertion above is measured. After the restore it is only
# required to be ALIVE and climbing, and its rate is printed rather than gated: a full-cluster
# restore is a new epoch and this proof's own ops notes say clients must reconnect — price-publisher
# IS a client, and whether it re-establishes its cluster session on its own is UNVERIFIED (see the
# trailer). Gating the post-restore rate would turn that open question into a red about DR, which is
# the failure mode this whole file was rewritten to remove.
#
# THIS PROOF DESTROYS THE ENTIRE CLUSTER. DESTRUCTIVE=1 is required; the default refuses and exits
# 2 without touching anything.
# ============================================================================================
#
# WHY GKE. The backup/restore path is GCS + HMAC + the member init container; it does not exist
# on kind. And a whole-cluster restore is an election from a seeded disk — timing behaviour that
# only means something on real hardware. (This drill was run live on 2026-07-19 — see
# docs/handoff/PROOF-yu12-gcs-backup-restore-2026-07-19.md; this script is that drill, captured
# and made falsifiable end to end.)
#
# The accounting that makes it falsifiable:
#   * the cluster is QUIESCED (of operator traffic), state S is captured, and only THEN is the
#     backup taken — so "restored == S" is an exact equality, not a fuzzy >=;
#   * AFTER the backup, more orders are deliberately placed (state S+). The restore must come back
#     at S, NOT S+ — proving it restored the backup rather than surviving in some replica; and the
#     post-backup orders are the honestly-stated DR loss window (RPO = backup interval);
#   * all three members must agree on the restored state (member-0 restores, 1 & 2 rejoin empty);
#   * the restored cluster books a NEW cross — a museum-piece restore that cannot trade fails here.
#
# Ops notes baked in from the 2026-07-19 drill: the backup CronJob `yu12-snapshot-backup` is
# normally SUSPENDED — this proof triggers a one-off Job from it; a full-cluster restore is a NEW
# epoch, so clients must reconnect (this script re-establishes its own tunnel + session); the
# disarm step's `set env` causes one more rolling restart (harmless, gated by catch-up).
#
# Usage: DESTRUCTIVE=1 ./yu12-gke-restore-from-gcs.sh   (GKE cluster up; HMAC secret + bucket in place)
set -euo pipefail

CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
STS="order-matcher-cluster"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu17-6374c110}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18340}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
TICKER="${TICKER:-DRP$(date +%H%M%S)}"
PRICE="${PRICE:-130.00}"
DESTRUCTIVE="${DESTRUCTIVE:-0}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-consensus-readings.sh"
. "${here}/lib-gke-replay-gates.sh"

member_metric() { ${K} exec "${STS}-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
state() { # "<orderHash> <positionHash> <trades> <nextRef>"
  ${K} exec "${STS}-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_order_hash/{o=$2} /^traderx_book_position_hash/{p=$2}
           /^traderx_cluster_trades/{t=$2} /^traderx_cluster_next_order_ref/{r=$2} END{print o,p,t,r}'
}
# THE THREE-MEMBER AGREEMENT CLAIM — member 0 restores from the tarball, members 1 and 2 rejoin
# from empty disks, and this is what says they all converged. It is NOT the restore-point claim any
# more; see the header. Retried, and the retry is load-bearing on this tier: three sequential execs
# with the tape moving all four quantities between them agreed on only 5 of 20 unretried attempts
# at 6.13/s (GKE bench, 2026-08-27).
identity_consensus() {
  local s0 s1 s2 i
  for i in $(seq 1 90); do
    s0="$(state 0 2>/dev/null)"; s1="$(state 1 2>/dev/null)"; s2="$(state 2 2>/dev/null)"
    # SHAPE, not emptiness — same defect as yu12-gke-recovery.sh carried, and it is worse here.
    # state()'s awk ends in END{print o,p,t,r}, which fires on NO INPUT with all four variables
    # unset and prints three spaces; `-n "   "` is true and all three "agree". This proof destroys
    # the cluster and asserts on what comes back, so under the old guard a run where the members
    # were unreachable at BOTH ends captured S = "   " and then confirmed "   " == "   " — a DR
    # proof that passes without either reading having happened.
    if [[ "${s0}" =~ ^-?[0-9]+\ -?[0-9]+\ [0-9]+\ [0-9]+$ \
       && "${s0}" == "${s1}" && "${s1}" == "${s2}" ]]; then echo "${s0}"; return 0; fi
    sleep 2
  done
  fail "members never reached byte-identity: [${s0}] [${s1}] [${s2}]
  (all-blank readings mean the members were UNREACHABLE, not that they disagreed)"
}
leader() { for m in 0 1 2; do [[ "$(member_metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }

PF_PID=""
stop_pf() { [[ -n "${PF_PID}" ]] && { kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; }; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!
  local t=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    t=$((t+1)); [[ ${t} -lt 120 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

order() { curl -s --max-time 30 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4}"; }

if [[ "${SELFTEST:-0}" == "1" ]]; then gates_selftest; exit $?; fi

require_destructive \
  "DESTROYS THE ENTIRE CLUSTER: it scales the StatefulSet to ZERO, which wipes every member's
       emptyDir, then restores member-0 from gs:// and rebuilds 1 and 2 from nothing. Everything any
       other lane has on this venue is GONE, back to the last backup. It also flips
       RESTORE_FROM_GCS on the StatefulSet and triggers a backup Job." \
  "state built and quiesced -> one-off backup Job -> post-backup traffic (the RPO window) ->
                whole-cluster wipe -> restore from gs:// -> operator state back at the BACKUP point and not
                the pre-wipe point, three members agreed -> the restored book trades." \
  "FILLER=5000"

# ---------------------------------------------------------------------------------------------
step "0. preflight: healthy cluster, live tape, gateway up — then build state worth restoring"
require_uniform_image "${IMAGE}"
require_tape_live
${K} get cronjob yu12-snapshot-backup >/dev/null 2>&1 || fail "backup cronjob yu12-snapshot-backup is not
  deployed on this tier, so there is nothing to take a backup FROM and this proof cannot run. It is
  absent on the yu17 bench cluster (checked 2026-08-27); the DR path needs the CronJob plus the GCS
  HMAC secret and bucket from the 2026-07-19 drill. This is a MISSING PREREQUISITE, not a failure of
  the restore path — do not report it as one."
PRESSURE0="$(pressure_row)"
RUN_T0=${SECONDS}
start_pf
for acct in "${ACCT}" "${OTHER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
order "${ACCT}"  Buy  6 "${PRICE}" >/dev/null                                  # rests
order "${OTHER}" Sell 4 "${PRICE}" >/dev/null                                  # crosses
order "${OTHER}" Sell 5 "$(python3 -c "print(${PRICE}+4)")" >/dev/null         # rests off-touch
# Volume filler: the backup job refuses tars under 100KB (its anti-empty-backup floor), and a
# fresh epoch's sparse log gzips to ~20KB. Rest FILLER non-crossing orders so the backup carries
# real weight — measured: 5000 ≈ 2.2MB on member-0's disk, comfortably past the gate.
FILLER="${FILLER:-5000}"
seq 1 "${FILLER}" | xargs -P 8 -I{} curl -s -o /dev/null --max-time 10 -X POST "${MATCHER_URL}/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":$(python3 -c "print(${PRICE}-10)"),\"clientOrderId\":\"drfill-$$-{}\"}"
echo "[ok] state built on ${TICKER} (resting orders + positions + trades + ${FILLER} filler orders)"

step "1. QUIESCE, capture S, then take the backup (a one-off Job from the suspended CronJob)"
S="$(identity_consensus)"
# THE BACKUP POINT, on state this proof owns. These two are what step 4 compares against, and they
# are the reason it can still make its claim: both are SNAPSHOTTED, so they go into the tarball and
# come back out of it, and replayed flow cannot move them while the restored cluster starts up.
# The filler above is deliberately NOT counted into an exact delta — some of those 5000 fire-and-
# forget sends may fail, and the baseline is taken here, after them, so it does not matter.
S_OPR="$(quiesced_order_refs)"; S_OPT="$(quiesced_trades)"
echo "  S (agreed, venue-wide) = [${S}]"
echo "  S (operator)           = refs ${S_OPR}, trade legs ${S_OPT}   <- what step 4 compares against"
JOB="yu12-backup-proof-$(date +%s)"
${K} create job --from=cronjob/yu12-snapshot-backup "${JOB}" >/dev/null || fail "could not create backup job"
${K} wait --for=condition=complete "job/${JOB}" --timeout=600s >/dev/null \
  || fail "backup job did not complete: $(${K} logs "job/${JOB}" --tail=5 2>/dev/null | tr '\n' ' ')"
# QUIESCE, SCOPED. The venue is NOT quiet across a multi-minute backup Job and has not been since
# ADR-072 — the old venue-wide equality here failed on every run and blamed quiesce. What the
# equality needs is that no OPERATOR wrote during the backup, so the tarball holds exactly the
# operator state captured above. Replayed flow is excluded from these counters at the writer.
S_OPR_CHK="$(quiesced_order_refs)"; S_OPT_CHK="$(quiesced_trades)"
[[ "${S_OPR_CHK}" == "${S_OPR}" && "${S_OPT_CHK}" == "${S_OPT}" ]] \
  || fail "an OPERATOR wrote during the backup (refs ${S_OPR} -> ${S_OPR_CHK}, trade legs ${S_OPT} -> ${S_OPT_CHK}),
  so the tarball does not hold the state captured above and step 4's equality would lie. The tape is
  excluded from these counters by construction, so this is the algo engine, another lane's proof, or
  a person with curl — not the replay."
echo "  backup complete; operator state still refs ${S_OPR}, trade legs ${S_OPT}"

step "2. post-backup traffic (state S+): the DR loss window, stated honestly"
order "${ACCT}" Buy 2 "$(python3 -c "print(${PRICE}+1)")" >/dev/null
order "${ACCT}" Buy 3 "$(python3 -c "print(${PRICE}+2)")" >/dev/null
SPLUS="$(identity_consensus)"
SPLUS_OPR="$(quiesced_order_refs)"; SPLUS_OPT="$(quiesced_trades)"
echo "  S+ (venue-wide) = [${SPLUS}]"
echo "  S+ (operator)   = refs ${SPLUS_OPR}, trade legs ${SPLUS_OPT}"
# ANTI-VACUITY, on the counter only our orders move. The old `SPLUS != S` compared the venue-wide
# state, which the tape changes ~6 times a second, so it held on a run where both post-backup
# orders had been dropped — the exact run it exists to catch. Two orders, neither crossing.
(( SPLUS_OPR - S_OPR == 2 )) \
  || fail "the post-backup orders moved the operator ref generator by $(( SPLUS_OPR - S_OPR )), not 2. Without a
  real gap between S and S+ the restore-vs-survive distinction in step 4 is untestable: both arms
  would be satisfied by the same reading."
# THE TAPE IS GATED HERE, BEFORE THE DESTROY, and not at the end of the run. Every assertion this
# file was rewritten for is measured in the window that closes on this line, so this is the window
# whose write pressure has to be real. Measuring instead across the whole run would span the minutes
# in which the cluster is scaled to ZERO and the tape cannot write at all, which would drag the
# observed rate under the band and red a correct run — a proof failing for the shape of its own
# timeline rather than for anything about DR.
assert_observed_rate "$(( SECONDS - RUN_T0 ))" "disaster recovery (pre-destroy window)"
PRESSURE_PRE="$(pressure_row)"

step "3. DESTROY the whole cluster (scale 0 wipes every emptyDir) and restore from GCS"
stop_pf
${K} scale statefulset/${STS} --replicas=0 >/dev/null
${K} wait --for=delete "pod/${STS}-0" "pod/${STS}-1" "pod/${STS}-2" --timeout=300s >/dev/null 2>&1 || true
echo "  all members gone — every disk is gone with them (emptyDir)"
${K} set env statefulset/${STS} RESTORE_FROM_GCS=1 --containers=restore-from-gcs >/dev/null
${K} scale statefulset/${STS} --replicas=3 >/dev/null
echo "  restore armed; member-0 seeds /data from latest.tgz, members 1 & 2 rejoin empty"
for i in $(seq 1 120); do
  ready="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{" "}{end}' 2>/dev/null)"
  [[ "${ready}" == "true true true " ]] && break
  [[ ${i} -lt 120 ]] || fail "restored members never all became ready (saw: ${ready})"
  sleep 5
done
leader >/dev/null || { for i in $(seq 1 90); do leader >/dev/null 2>&1 && break; sleep 2; done; }
leader >/dev/null || fail "no leader after restore"
# Disarm BEFORE asserting: the disarm restart is part of the runbook and must not be able to
# change the verdict. It rolls the members once more (catch-up gated).
${K} set env statefulset/${STS} RESTORE_FROM_GCS=0 --containers=restore-from-gcs >/dev/null
${K} rollout status statefulset/${STS} --timeout=600s >/dev/null 2>&1 || true
echo "  restore disarmed (one more rolling restart, catch-up gated)"

step "4. the verdict: restored to the BACKUP point — not the pre-wipe point, not empty"
R="$(identity_consensus)"
R_OPR="$(quiesced_order_refs)"; R_OPT="$(quiesced_trades)"
echo "  restored (operator) = refs ${R_OPR}, trade legs ${R_OPT}"
echo "  backup point   S    = refs ${S_OPR}, trade legs ${S_OPT}"
echo "  pre-wipe       S+   = refs ${SPLUS_OPR}, trade legs ${SPLUS_OPT}"
echo "  (venue-wide agreed state is [${R}]; it is NOT compared to [${S}] — replayed flow moves it"
echo "   continuously and the restored cluster resumes taking that flow the instant it is up.)"
# THE RESTORE-VS-SURVIVE DISCRIMINATOR, on snapshotted counters no other writer can move. This is
# what the venue-wide `R == S` was for, and it is the reading that still works.
[[ "${R_OPR}" == "${S_OPR}" && "${R_OPT}" == "${S_OPT}" ]] || {
  [[ "${R_OPR}" == "${SPLUS_OPR}" && "${R_OPT}" == "${SPLUS_OPT}" ]] \
    && fail "restored operator state equals S+ (refs ${SPLUS_OPR}, legs ${SPLUS_OPT}) — the cluster SURVIVED rather
  than restored, and the DR path was never exercised. These counters are snapshotted, so coming back
  at the PRE-WIPE value means the wipe did not take."
  fail "restored operator state (refs ${R_OPR}, legs ${R_OPT}) matches neither the backup point (refs ${S_OPR},
  legs ${S_OPT}) nor the pre-wipe state (refs ${SPLUS_OPR}, legs ${SPLUS_OPT}). These are snapshotted counters that
  no other writer on this venue can move, so this is the restore itself losing or inventing state."
}
echo "  ✔ intact at the backup point; the $(( SPLUS_OPR - S_OPR )) post-backup order(s) are the stated RPO loss window"
echo "  ✔ all three members agreed on the restored state (member-0 from the tarball, 1 & 2 from empty)"

step "5. the restored cluster takes NEW business (a cross books on all three members)"
start_pf   # new epoch: clients must reconnect — including this one
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null 2>&1 || true
T_OPR0="$(quiesced_order_refs)"; T_OPT0="$(quiesced_trades)"
order "${ACCT}" Buy 5 "$(python3 -c "print(${PRICE}+4)")" >/dev/null   # crosses the restored +4 sell
T_OPR1="$(quiesced_order_refs)"; T_OPT1="$(quiesced_trades)"
assert_order_effects "${T_OPR0}" "${T_OPR1}" 1 "${T_OPT0}" "${T_OPT1}" 2 \
  "the RESTORED resting order did not fill — the restored book is not live state"
echo "  operator refs ${T_OPR0} -> ${T_OPR1}, trade legs ${T_OPT0} -> ${T_OPT1} (2 legs, one match)"
(( T_OPR1 > S_OPR )) \
  || fail "the operator ref generator (${S_OPR} at the backup point -> ${T_OPR1} now) did not continue past the
  restored point: a restore that reissues ids from the backup is cross-epoch id reuse."

step "6. the write pressure this run actually ran under"
# The pre-destroy window is the one that was GATED (end of step 2) — see the note there. This table
# is the whole run, so a future reader can see how much foreign flow crossed the venue while these
# assertions were made, and against how little of our own.
echo "  across the PRE-DESTROY window, which is the one the rate gate covers:"
print_pressure "${PRESSURE0}" "${PRESSURE_PRE}"
echo "  across the WHOLE run, including the wipe and restore (the cluster was down for part of it,"
echo "  and the counters below were reset to the backup point by the restore — read as context only):"
print_pressure "${PRESSURE0}" "$(pressure_row)"
POST_H="$(_pub /health)"
POST_ERR="$(printf '%s' "${POST_H}" | _jget printReplay.error)" || POST_ERR=""
POST_SUB="$(printf '%s' "${POST_H}" | _jget printReplay.submitted)" || POST_SUB=""
POST_RATE="$(printf '%s' "${POST_H}" | _jget printReplay.ordersPerSecond)" || POST_RATE=""
echo "  tape AFTER the restore: submitted ${POST_SUB:-?}, reported ${POST_RATE:-?}/s, error=${POST_ERR:-none}"
[[ -z "${POST_ERR}" ]] \
  || echo "    NOTE: the replay reports an error after the restore. A full-cluster restore is a new
    epoch and every client must reconnect; price-publisher is a client. That is a finding about the
    RESTORE RUNBOOK (and worth filing), not about the assertions above, which were all measured in
    the pre-destroy window this step gates."

echo
echo "[PASS] disaster recovery: whole-cluster loss, restored from gs:// to exactly the backup"
echo "       point — this proof's own snapshotted operator state came back at S and not at S+,"
echo "       all three members agreed, the post-backup orders are correctly bounded as the RPO"
echo "       window, and the restored book trades."
