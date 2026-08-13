#!/usr/bin/env bash
# yu12-gke-restore-from-gcs.sh — the DR proof: destroy the WHOLE cluster (all three members,
# emptyDir — state gone from every disk) → bring it back with RESTORE_FROM_GCS=1 → the book,
# positions, trade counter and reference generator are INTACT at exactly the last GCS backup —
# and the restored cluster takes new business.
#
# WHY GKE. The backup/restore path is GCS + HMAC + the member init container; it does not exist
# on kind. And a whole-cluster restore is an election from a seeded disk — timing behaviour that
# only means something on real hardware. (This drill was run live on 2026-07-19 — see
# docs/handoff/PROOF-yu12-gcs-backup-restore-2026-07-19.md; this script is that drill, captured
# and made falsifiable end to end.)
#
# The accounting that makes it falsifiable:
#   * the cluster is QUIESCED, state S is captured (4 agreed quantities), and only THEN is the
#     backup taken — so "restored == S" is an exact equality, not a fuzzy >=;
#   * AFTER the backup, more orders are deliberately placed (state S+). The restore must come back
#     at S, NOT S+ — proving it restored the backup rather than surviving in some replica; and the
#     post-backup orders are the honestly-stated DR loss window (RPO = backup interval);
#   * all three members must agree on the restored state (member-0 restores, 1 & 2 rejoin empty);
#   * the restored cluster books a NEW cross (trades move on all three) — a museum-piece restore
#     that cannot trade fails here.
#
# Ops notes baked in from the 2026-07-19 drill: the backup CronJob `yu12-snapshot-backup` is
# normally SUSPENDED — this proof triggers a one-off Job from it; a full-cluster restore is a NEW
# epoch, so clients must reconnect (this script re-establishes its own tunnel + session); the
# disarm step's `set env` causes one more rolling restart (harmless, gated by catch-up).
#
# Usage: ./yu12-gke-restore-from-gcs.sh   (GKE cluster up; HMAC secret + bucket in place)
set -euo pipefail

CTX="${CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
STS="order-matcher-cluster"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18340}"
ACCT="${ACCT:-42422}"
OTHER="${OTHER:-22214}"
TICKER="${TICKER:-DRP$(date +%H%M%S)}"
PRICE="${PRICE:-130.00}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

member_metric() { ${K} exec "${STS}-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
state() { # "<orderHash> <positionHash> <trades> <nextRef>"
  ${K} exec "${STS}-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_order_hash/{o=$2} /^traderx_book_position_hash/{p=$2}
           /^traderx_cluster_trades/{t=$2} /^traderx_cluster_next_order_ref/{r=$2} END{print o,p,t,r}'
}
identity_consensus() { # retried: one early sample looks like divergence and is not
  local s0 s1 s2 i
  for i in $(seq 1 90); do
    s0="$(state 0 2>/dev/null)"; s1="$(state 1 2>/dev/null)"; s2="$(state 2 2>/dev/null)"
    # SHAPE, not emptiness — same defect as yu12-gke-recovery.sh carried, and it is worse here.
    # state()'s awk ends in END{print o,p,t,r}, which fires on NO INPUT with all four variables
    # unset and prints three spaces; `-n "   "` is true and all three "agree". This proof captures
    # state S from a QUIESCED cluster, destroys the cluster, restores from GCS and asserts the
    # restored state equals S exactly. Under the old guard a run where the members were
    # unreachable at BOTH ends captured S = "   " and then confirmed "   " == "   " — a DR proof
    # that passes without either reading having happened.
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

# ---------------------------------------------------------------------------------------------
step "0. preflight: healthy cluster, gateway live, seeded — then build state worth restoring"
for i in $(seq 1 60); do
  ready="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{" "}{end}')"
  [[ "${ready}" == "true true true " ]] && break
  [[ ${i} -lt 60 ]] || fail "members never all ready (saw: ${ready})"
  sleep 5
done
${K} get cronjob yu12-snapshot-backup >/dev/null 2>&1 || fail "backup cronjob yu12-snapshot-backup is not deployed"
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
echo "  S (agreed, quiesced) = [${S}]"
JOB="yu12-backup-proof-$(date +%s)"
${K} create job --from=cronjob/yu12-snapshot-backup "${JOB}" >/dev/null || fail "could not create backup job"
${K} wait --for=condition=complete "job/${JOB}" --timeout=600s >/dev/null \
  || fail "backup job did not complete: $(${K} logs "job/${JOB}" --tail=5 2>/dev/null | tr '\n' ' ')"
# No traffic ran between capture and backup, so the tarball holds exactly S.
S_CHECK="$(identity_consensus)"
[[ "${S_CHECK}" == "${S}" ]] || fail "state moved during the backup ([${S}] -> [${S_CHECK}]) — quiesce violated, equality would lie"
echo "  backup complete; state still [${S}]"

step "2. post-backup traffic (state S+): the DR loss window, stated honestly"
order "${ACCT}" Buy 2 "$(python3 -c "print(${PRICE}+1)")" >/dev/null
order "${ACCT}" Buy 3 "$(python3 -c "print(${PRICE}+2)")" >/dev/null
SPLUS="$(identity_consensus)"
echo "  S+ = [${SPLUS}]"
[[ "${SPLUS}" != "${S}" ]] || fail "post-backup orders changed nothing — the restore-vs-survive distinction would be untestable"

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

step "4. the verdict: restored state is EXACTLY S — not S+, not empty — on all three members"
R="$(identity_consensus)"
echo "  restored (agreed) = [${R}]"
echo "  backup point   S  = [${S}]"
echo "  pre-wipe       S+ = [${SPLUS}]"
[[ "${R}" == "${S}" ]] || {
  [[ "${R}" == "${SPLUS}" ]] && fail "restored state equals S+ — the cluster SURVIVED rather than restored; the DR path was not exercised"
  fail "restored state matches neither the backup nor the pre-wipe state — restore is corrupt"
}
echo "  ✔ intact at the backup point; the ${SPLUS##* }-vs-${S##* } ref gap is the stated RPO loss window"

step "5. the restored cluster takes NEW business (a cross books on all three members)"
start_pf   # new epoch: clients must reconnect — including this one
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null 2>&1 || true
T0="$(echo "$(identity_consensus)" | awk '{print $3}')"
order "${ACCT}" Buy 5 "$(python3 -c "print(${PRICE}+4)")" >/dev/null   # crosses the restored +4 sell
POST="$(identity_consensus)"; T1="$(echo "${POST}" | awk '{print $3}')"
echo "  trades: ${T0} -> ${T1} (agreed)"
[[ "${T1}" -eq "$(( T0 + 2 ))" ]] \
  || fail "the RESTORED resting order did not fill — the restored book is not live state"
[[ "${POST##* }" -gt "${S##* }" ]] || fail "reference generator did not continue past the restored point"

echo
echo "[PASS] disaster recovery: whole-cluster loss, restored from gs:// to exactly the backup"
echo "       point (order hash, position hash, trades, nextOrderRef agreed by all three members),"
echo "       post-backup orders correctly bounded as the RPO window, and the restored book trades."
