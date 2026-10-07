#!/usr/bin/env bash
# YU05 — PROOF: reconciliation is the CQRS integrity check. It classifies the authoritative journal
# blotter against the MariaDB projection (MATCHED / MISSING_IN_PROJECTION / FIELD_MISMATCH), and an
# on-demand full-history sweep flags ORPHAN_IN_PROJECTION — a projection row with no journal fill
# behind it (FR-PTC04/05/10). This is how you PROVE the async read-model hasn't drifted.
#
# ON THIS TIER THE JOURNAL IS THE RAFT LOG. The members serve /recon/* by replaying the Aeron
# Archive's cluster-log recording through a shadow engine (ClusterRecon); the gateway forwards to a
# member because it holds no history itself. THE SOURCE IS THE POINT: serving these trades from the
# SQL projection would compare SQL against itself and pass vacuously with matched=0, so this proof
# asserts against the LOG side at every step — the replay's trade population is bracketed by the
# live engine's own counter, and the orphan verdict is exercised with a planted row before it is
# believed.
#
# Prereq: source terminals in yu05-common.sh.
# Usage: bash yu05-recon.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
ADMIN=$(mint true '[]')
FAIL=0
say(){ printf "   %-30s %s\n" "$1" "$2"; }
bad(){ echo "   ✘ $*"; FAIL=1; }
num(){ case "${1:-}" in ''|*[!0-9-]*) echo "" ;; *) echo "$1" ;; esac; }

echo "── RECONCILIATION (journal ↔ projection) ──"

# ---- 1. the full-history reindex: does this tier serve the contract at all? -------------------
# One call, code and body together: the reindex replays the whole log, so probing with a throwaway
# request first would pay for it twice.
RESP=$(curl -s -m600 -w $'\n%{http_code}' -X POST "$OM/recon/full-history/reindex" \
  -H "Authorization: Bearer $ADMIN"); RI_RC=$?
RI_CODE=$(printf '%s' "$RESP" | tail -1)
RI=$(printf '%s' "$RESP" | sed '$d')
case "$RI_CODE" in
  000)
    # Unreachable is an error, not a green light: treating it as "capability present" let this
    # script run on to report matched=0 against a matcher it never contacted.
    # No remedy named here on purpose: how $OM is reached differs per rig (a forward on kind, a
    # LoadBalancer with a public IP on GKE), so naming one sends half the readers to build
    # something that is not in their path while the real cause goes unexamined.
    # rc 7 and rc 28 are NOT the same finding — same split as yu05-regulatory-reproducible.sh.
    # 7 is nothing listening (the transport). 28 is no answer inside the budget, which on a
    # flood-scale epoch is a busy member, not an absent one.
    if [ "$RI_RC" = "28" ]; then
      echo "   ✘ no answer from $OM/recon/full-history/reindex within the budget (curl rc=28)."
      echo "     A TIMEOUT, not a transport fault and not a verdict about recon: something is"
      echo "     listening, it did not answer in time. A full-history reindex replays the whole"
      echo "     log, so on a large epoch it legitimately outlasts a short client budget. Check"
      echo "     the member (kubectl logs order-matcher-cluster-0) before reading this as broken."
      exit 1
    fi
    echo "   ✘ the order-matcher is not reachable at $OM (curl rc=$RI_RC — 7 is nothing"
    echo "     listening). Nothing is on the other end, so this is the transport, not recon."
    exit 1 ;;
  404)
    echo "   ✘ $OM/recon/full-history/reindex -> 404"
    echo "   CONTRACT (from ReconciliationService, YU05 layer) — this tier must serve all three:"
    echo "     GET  /recon/trades/blotter?sinceSeq=N     -> 200, the live forward window"
    echo "     POST /recon/full-history/reindex          -> 200"
    echo "     GET  /recon/full-history/trades?sinceSeq=N -> 200, a page of BlotterEntry"
    echo "   A build predating ClusterRecon serves none of them. Rebuild the member image"
    echo "   (scripts/yu15/build-cluster-image.sh) and roll to a FRESH EPOCH."
    exit 2 ;;
  401|403)
    bad "admin JWT rejected by the member ($RI_CODE) — AUTH_JWT_SECRET mismatch between"
    echo "     trade-processor and the order-matcher-cluster StatefulSet, not a recon result."
    exit 1 ;;
  503)
    # A rig fault, deliberately NOT a stated skip: "recon switched off" is a statement about this
    # deployment's env, and reporting it as a capability verdict about the tier is exactly the
    # precondition-as-verdict confusion these proofs exist to refuse.
    bad "members answer 503 — RECON_BLOTTER_CAPACITY unset on order-matcher-cluster."
    echo "     The capability exists in this build; this rig has it disabled. Set it and re-roll."
    exit 1 ;;
  200) : ;;
  *) bad "unexpected $RI_CODE from /recon/full-history/reindex: $RI"; exit 1 ;;
esac

INDEXED=$(num "$(printf '%s' "$RI" | jfield "d['indexedTrades']")")
REPLAYED=$(num "$(printf '%s' "$RI" | jfield "d['replayedMessages']")")
RSEQ=$(num "$(printf '%s' "$RI" | jfield "d['replayedAppliedSeq']")")
TC_BEFORE=$(num "$(printf '%s' "$RI" | jfield "d['liveTradeCounterBefore']")")
TC_AFTER=$(num "$(printf '%s' "$RI" | jfield "d['liveTradeCounterAfter']")")
say "log messages replayed"   "$REPLAYED"
say "replay applied sequence" "$RSEQ"
say "indexed journal trades"  "$INDEXED"
say "live engine tradeCounter" "${TC_BEFORE:-?} .. ${TC_AFTER:-?}"

if [ -z "$INDEXED" ] || [ -z "$TC_BEFORE" ] || [ -z "$TC_AFTER" ] || [ -z "$REPLAYED" ]; then
  bad "reindex answered 200 with an unreadable body: $RI"
elif [ "$REPLAYED" -le 0 ]; then
  bad "replayed 0 log messages — the archive replay found nothing; an empty index would report"
  echo "     every projection row as an orphan, so this is a failure, not a clean sweep."
elif [ "$INDEXED" -le 0 ]; then
  bad "indexed 0 trades from a log of $REPLAYED messages"
elif [ "$INDEXED" -lt "$TC_BEFORE" ] || [ "$INDEXED" -gt "$TC_AFTER" ]; then
  # THE assertion that the replay is real. The index is built from a fixed prefix of a log that
  # keeps moving, so it can only be bracketed — but a replay that reconstructs a different trade
  # population than the live engine holds is a broken replay, and this is where that shows.
  bad "replayed trade population $INDEXED outside the live engine's [$TC_BEFORE, $TC_AFTER]"
  echo "     — a from-zero replay of the committed log must reproduce the engine's own trades."
else
  echo "   → the log replayed from zero reproduces the live engine's trade population ✔"
fi

# ---- 2. cross-member: the answer is a function of the LOG, not of one pod ---------------------
# Two members replay their own archive independently. The claim is that the index is derived from
# replicated state rather than from whatever one pod happens to hold.
#
# IT USED TO BE AN EQUALITY, AND THAT WAS ONLY EVER TRUE ON A QUIET LOG. Each reindex replays that
# member's archive UP TO NOW, so two reindexes taken seconds apart legitimately cover different
# prefixes of a log that is still growing. Before ADR-072 the only writer was the price feed, which
# books no trades, so the trade population happened to hold still between the two calls and the
# equality passed for a reason that had nothing to do with cross-member determinism. Since the tape
# replay became an order writer it does not hold still: measured 2026-08-26 on the first suite run
# with the replay live, 608 vs 624, on a cluster in perfect agreement — the check accusing the
# members of a divergence that the same numbers disprove.
#
# THE REPAIR IS A BRACKET, NOT A TOLERANCE. Reindex member 0, then member 1, then member 0 AGAIN.
# Member 0's two readings bound exactly the growth the log took while member 1 was replaying, so
# member 1 must land inside them — on BOTH the trade count and the consensus position it replayed
# to. The interval is measured, not chosen, and it collapses to the old equality the moment nothing
# is writing. A member replaying a stale or divergent archive falls outside it, and the SEQUENCE
# half is what catches the case the count could hide: a member that stopped following the log
# reports a lower replayedAppliedSeq no matter how many trades it happens to have indexed.
member_reindex(){ $K exec "order-matcher-cluster-$1" -- sh -c \
  "wget -qO- --post-data='' --header='Authorization: Bearer $ADMIN' \
   http://localhost:8080/recon/full-history/reindex" 2>/dev/null \
  | jfield "str(d['indexedTrades']) + ' ' + str(d['replayedAppliedSeq'])"; }
read -r M0A S0A <<EOF
$(member_reindex 0)
EOF
read -r M1 S1 <<EOF
$(member_reindex 1)
EOF
read -r M0B S0B <<EOF
$(member_reindex 0)
EOF
say "member 0 index / seq" "${M0A:-?} .. ${M0B:-?} / ${S0A:-?} .. ${S0B:-?}"
say "member 1 index / seq" "${M1:-?} / ${S1:-?}"
if [ -z "$M0A" ] || [ -z "$M1" ] || [ -z "$M0B" ] || [ -z "$S0A" ] || [ -z "$S1" ] || [ -z "$S0B" ]; then
  bad "could not read a per-member reindex — cross-member determinism unproven"
elif [ "$M0A" -gt "$M0B" ] || [ "$S0A" -gt "$S0B" ]; then
  # One member's own readings must be monotone; if they are not, the bracket below is meaningless
  # and the member is the thing that is wrong.
  bad "member 0 went BACKWARDS between its own two reindexes ($M0A->$M0B trades, $S0A->$S0B seq)
     — the log does not shrink, so this is the member losing history, not the bracket being loose"
elif [ "$S1" -lt "$S0A" ] || [ "$S1" -gt "$S0B" ]; then
  bad "member 1 replayed to consensus position $S1, outside the [$S0A, $S0B] member 0 bracketed it
     with — it is not following the same log"
elif [ "$M1" -lt "$M0A" ] || [ "$M1" -gt "$M0B" ]; then
  bad "members disagree on the replayed history: member 1 indexed $M1 trades from a log member 0
     bracketed at [$M0A, $M0B] over the same window — the index is not a function of the log"
else
  echo "   → both members replay their own archive to the same history ✔ (member 1 inside member"
  echo "     0's own [$M0A, $M0B] bracket, at consensus position $S1 in [$S0A, $S0B])"
fi

# ---- 3. the forward sweep: trade-processor classifying the log against its projection ---------
# CLASSIFICATION IS ONCE-PER-ENTRY AND COUNTERS ARE POD-LIFETIME CUMULATIVE (ReconciliationService:
# the cursor only ever advances and matched/missing/fieldMismatch are LongAdders), so /recon/status
# is NOT a reading of the current state — it is the union of every classification this pod ever
# made, including ones taken mid-epoch-churn. Measured 2026-08-18: field_mismatch=4 classified
# while a fresh epoch΄s projection was mid-reseed persisted in the counters while this proof΄s own
# full-history arm was clean, and a pod replacement (cursor rebuilt from 0 over the stable state)
# read 0. A moment-in-time counter is not a verdict, and neither is a stale cumulative one.
#
# So: RESTART trade-processor and judge the from-zero classification of the SETTLED state, once.
# This is not retry-until-zero — exactly one fresh classification is judged, and a persistent
# mismatch is present in every fresh classification, proven by the planted control in 3b which
# rides the same mechanism. The restart kills the runner΄s port-forward, so this proof owns its own
# from here (prove-cluster-engine-change §5: a dead tunnel is indistinguishable from an absent
# feature).
echo "   ── forward sweep (fresh classification, journal blotter → projection) ──"
ENGINE_T=$($K exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/metrics' \
  2>/dev/null | awk '/^traderx_cluster_trades/ {print $2}')
for _ in $(seq 1 30); do
  SQLT=$(dbq "SELECT COUNT(*) FROM trades;")
  [ "${SQLT:-0}" -ge "${ENGINE_T:-1}" ] && break
  sleep 2
done
TP_PF_PID=""
own_tp_forward(){
  [ -n "$TP_PF_PID" ] && kill "$TP_PF_PID" 2>/dev/null
  pkill -f "port-forward deploy/trade-processor" 2>/dev/null; sleep 1
  $K port-forward deploy/trade-processor 18091:18091 >/dev/null 2>&1 & TP_PF_PID=$!
  local t=0
  until [ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18091/actuator/health)" = "200" ]; do
    t=$((t+1)); [ $t -lt 60 ] || return 1
    kill -0 "$TP_PF_PID" 2>/dev/null || { $K port-forward deploy/trade-processor 18091:18091 >/dev/null 2>&1 & TP_PF_PID=$!; }
    sleep 2
  done
}
recon_status(){ curl -s -m8 "$TP/recon/status" -H "Authorization: Bearer $ADMIN"; }
# WAIT FOR THE SWEEP TO FINISH, NOT FOR IT TO START.
#
# This returned on the first poll where matched > 0 — i.e. MID-SWEEP — and that is a race whose
# probability grows with the epoch. Measured 2026-08-25 on a 42-trade epoch: the status came back
# `matched 22` against `engine trades / SQL rows 42 / 42`, so classification had covered half the
# projection when it was read. 3b plants its mismatch on `SELECT id FROM trades ORDER BY id LIMIT 1`
# — the OLDEST row — which the sweep reaches last, so the control read fieldMismatch=0 and the proof
# failed with "a PLANTED persistent mismatch was NOT caught". Nothing was wrong with the classifier.
# On a smaller epoch the whole sweep fit inside one 2s poll and it passed, which is why this only
# appeared once the suite had been run twice on one epoch.
#
# Returning early is worse for 3b than for the verdict it guards: a half-finished sweep reports
# fieldMismatch=0, which is exactly what a CLEAN projection reports. The control cannot tell "no
# mismatch" from "not looked yet", so the negative control silently stops being one.
#
# The gate is QUIESCENCE, the same rule the cross-member readings use: hold until the whole triple
# (matched / missingInProjection / fieldMismatch) is unchanged across three consecutive reads. It
# still fails loudly if classification never runs at all (matched stays 0 through the timeout), so
# nothing is weakened — a mismatch that IS present is now guaranteed to have been looked for.
#
# QUIESCENCE IS NOT ENOUGH, AND THE FAILURE IS DETERMINISTIC RATHER THAN FLAKY. Measured
# 2026-08-25 on a 226-trade epoch: this proof FAILED at 3b with "a PLANTED persistent mismatch was
# NOT caught", reading `matched 52` against `SQL rows 222`. Same build, same code, re-run on a
# fresh 20-trade epoch minutes later: PASS. The discriminator is EPOCH SIZE, not the build —
# confirmed separately by polling /recon/status for 80s of real stillness after a restart, which
# settled at matched=20 against 20 rows, i.e. the classifier covers every row and the gate is what
# returns early.
#
# The arithmetic, which is why it cannot be a race that "sometimes" bites:
#     RECON_POLL_INTERVAL_MS = 10000        the sweep advances once per 10s
#     this gate: sleep 2, stable>=2         ~4-6s of stillness is enough to return
# The triple therefore sits unchanged for ~5 consecutive polls BETWEEN pages, and "between pages"
# is indistinguishable from "finished". Any epoch needing more than one page returns mid-sweep,
# and 3b plants on the OLDEST row — which the sweep reaches last.
#
# DO NOT FIX THIS BY WIDENING THE WINDOW. That works today and breaks at the next epoch size or
# interval change; it is how this gate got here. Stillness is a PROXY for "the classification
# finished"; the property is "the classification covered every row". Gate on that instead.
#
# It is deliberately NOT fixed here yet, because `matched` and `cursor` do not track in a way
# anyone has explained: the failing run read matched 52 / cursor 226 / rows 222, the passing run
# read 20 / 20 / 20. A completeness gate built on a wrong model of what `matched` counts would
# fail LOUDLY and WRONGLY, which is worse than passing quietly and wrongly — it looks like rigour
# and sends people after phantom defects. Settle what `matched` counts relative to `cursor` in
# ReconciliationService first; the gate is three lines after that, and a guess before it.
fresh_classification(){ # restart TP, re-own the forward, echo the SETTLED from-zero classification
  $K rollout restart deploy/trade-processor >/dev/null 2>&1
  $K rollout status deploy/trade-processor --timeout=300s >/dev/null 2>&1 || return 1
  own_tp_forward || return 1
  local s m triple prev="" stable=0
  for _ in $(seq 1 90); do
    s=$(recon_status)
    m=$(num "$(printf '%s' "$s" | jfield "d['matched']")")
    triple="$(printf '%s' "$s" | jfield "str(d['matched'])+'/'+str(d['missingInProjection'])+'/'+str(d['fieldMismatch'])" 2>/dev/null)"
    if [ -n "$m" ] && [ "$m" -gt 0 ] && [ -n "$triple" ] && [ "$triple" = "$prev" ]; then
      stable=$((stable + 1))
      [ "$stable" -ge 2 ] && { printf '%s' "$s"; return 0; }
    else
      stable=0
    fi
    prev="$triple"
    sleep 2
  done
  return 1
}
if ! S=$(fresh_classification); then
  bad "no fresh classification arrived after a trade-processor restart — sweep not running or TP unreachable"
  S=$(recon_status)
fi
MATCHED=$(num "$(printf '%s' "$S" | jfield "d['matched']")")
MISSING=$(num "$(printf '%s' "$S" | jfield "d['missingInProjection']")")
MISMATCH=$(num "$(printf '%s' "$S" | jfield "d['fieldMismatch']")")
say "matched"               "${MATCHED:-?} (fresh classification)"
say "missing_in_projection" "${MISSING:-?}"
say "field_mismatch"        "${MISMATCH:-?}"
say "journal cursor"        "$(printf '%s' "$S" | jfield "d['cursor']")"
if [ -z "$MATCHED" ] || [ "$MATCHED" -le 0 ]; then
  bad "the sweep classified 0 trades — matched=0 is a clean reconciliation of NOTHING, which is"
  echo "     what this proof exists to refuse. Check RECON_POLL_INTERVAL_MS on trade-processor."
elif [ "${MISMATCH:-1}" -ne 0 ]; then
  bad "field_mismatch=$MISMATCH on a FRESH classification of the settled state — the projection disagrees with the log"
else
  echo "   → $MATCHED journal-sourced trades match the projection field for field ✔ (fresh classification)"
fi
# missing_in_projection: fresh pod, but the bridge is asynchronous — a trade delivered between the
# blotter page and the projection read counts once. Lag signal, not drift; the set comparison below
# is the drift verdict.
[ "${MISSING:-0}" -gt 0 ] && echo "     (missing=$MISSING is bridge lag counted at classification time; the set comparison below is the verdict)"

# ---- 3b. negative control: a PERSISTENT field mismatch must fail a fresh classification -------
# Mutate a positively identified SQL/live-window row, require its exact id in a fresh
# processor's mismatch log, restore, and require clean reclassification of that subject.
# Counters alone cannot identify which row failed. The helper restores on EXIT/INT/TERM/HUP;
# SIGKILL or loss of database/control-plane connectivity cannot guarantee remote restoration.
# YU18 persists scoped recon checkpoints. The helper pauses the sole processor replica,
# saves/resets only the selected scope, observes the exact subject in a NEW pod's logs,
# and restores subject/checkpoint/replicas even on catchable interruption. Legacy/unknown
# scope refuses instead of attributing an unqualified id to the current run.
python3 "$here/recon-proof-subject.py" --context "$CTX" --db-deploy "$DB_DEPLOY" --om "$OM" \
  --attempts "${RECON_PROOF_ATTEMPTS:-90}" --poll-seconds "${RECON_PROOF_POLL_SECONDS:-2}" <<<"$ADMIN" &
SUBJECT_PID=$!
subject_interrupted(){
  trap '' INT TERM HUP
  kill -TERM "$SUBJECT_PID" 2>/dev/null || true
  wait "$SUBJECT_PID" || true
  exit 1
}
trap subject_interrupted INT TERM HUP
wait "$SUBJECT_PID"; SUBJECT_RC=$?
trap - INT TERM HUP
if [ "$SUBJECT_RC" -ne 0 ]; then
  bad "exact scoped subject control failed or refused; see diagnostic above"
  # Do not continue into the orphan mutation after a failed cleanup/precondition.
  exit 1
fi

# ---- 4. orphan sweep: every projection row must have journal provenance -----------------------
echo "   ── full-history sweep (admin): every projection row vs the whole log ──"
# Let the projection settle first: comparing whole sets while the bridge is still delivering
# would report a lagging row as an orphan, which is a verdict about timing, not about drift.
ENGINE_TRADES=$($K exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/metrics' \
  2>/dev/null | awk '/^traderx_cluster_trades/ {print $2}')
for _ in $(seq 1 30); do
  SQL_TRADES=$(dbq "SELECT COUNT(*) FROM trades;")
  [ "${SQL_TRADES:-0}" -ge "${ENGINE_TRADES:-1}" ] && break
  sleep 2
done
say "engine trades / SQL rows" "${ENGINE_TRADES:-?} / ${SQL_TRADES:-?}"

# The same proof helper owns a unique, attributed projection-only row for this invocation.
# Its INSERT is scoped atomically, and cleanup reconciles ambiguous outcomes with full-row
# ownership predicates. Catchable signals wait for the bounded operation and qualified cleanup.
python3 "$here/recon-proof-subject.py" --mode orphan --context "$CTX" \
  --db-deploy "$DB_DEPLOY" --om "$OM" --attempts "${RECON_PROOF_ATTEMPTS:-90}" \
  --poll-seconds "${RECON_PROOF_POLL_SECONDS:-2}" <<<"$ADMIN" &
SUBJECT_PID=$!
trap subject_interrupted INT TERM HUP
wait "$SUBJECT_PID"; ORPHAN_RC=$?
trap - INT TERM HUP
if [ "$ORPHAN_RC" -ne 0 ]; then
  bad "attributed orphan control failed or refused; see diagnostic above"
  exit 1
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "   ✔ RECONCILED against the replicated log: the projection is a faithful read model, and"
  echo "     the check that says so is demonstrably able to fail."
else
  echo "   ✘ reconciliation FAILED — see above"
fi
exit $FAIL
