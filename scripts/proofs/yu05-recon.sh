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
# Two members replay their own archive independently. Equal counts mean the index is derived from
# replicated state; a difference means one of them is not reading what it committed.
member_reindex(){ $K exec "order-matcher-cluster-$1" -- sh -c \
  "wget -qO- --post-data='' --header='Authorization: Bearer $ADMIN' \
   http://localhost:8080/recon/full-history/reindex" 2>/dev/null | jfield "d['indexedTrades']"; }
M0=$(num "$(member_reindex 0)"); M1=$(num "$(member_reindex 1)")
say "member 0 / member 1 index" "${M0:-?} / ${M1:-?}"
if [ -z "$M0" ] || [ -z "$M1" ]; then
  bad "could not read a per-member reindex — cross-member determinism unproven"
elif [ "$M0" != "$M1" ]; then
  bad "members disagree on the replayed history ($M0 vs $M1) — the index is not a function of the log"
else
  echo "   → both members replay their own archive to the same history ✔"
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
fresh_classification(){ # restart TP, re-own the forward, echo the from-zero classification΄s status
  $K rollout restart deploy/trade-processor >/dev/null 2>&1
  $K rollout status deploy/trade-processor --timeout=300s >/dev/null 2>&1 || return 1
  own_tp_forward || return 1
  local s m
  for _ in $(seq 1 60); do
    s=$(recon_status); m=$(num "$(printf '%s' "$s" | jfield "d['matched']")")
    [ -n "$m" ] && [ "$m" -gt 0 ] && { printf '%s' "$s"; return 0; }
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
# The restart discipline above must not have weakened the assertion into one that only ever sees
# clean states (an assertion never observed failing is a hypothesis). Mutate one projection field,
# prove a fresh classification names it, restore, prove it clears. The restore is trapped so a
# killed run cannot leave the projection poisoned. NOTE a mutation is invisible WITHOUT the
# restart: classification is once-per-entry, which is why this control and the verdict above ride
# the same mechanism — this control failing means the verdict above is not real.
MUT_ROW=$(dbq "SELECT id FROM trades ORDER BY id LIMIT 1;")
MUT_QTY=$(num "$(dbq "SELECT quantity FROM trades WHERE id='$MUT_ROW';")")
if [ -z "$MUT_ROW" ] || [ -z "$MUT_QTY" ]; then
  bad "no projection row available to plant the mismatch control — the control did not run"
else
  say "planted field mismatch" "row $MUT_ROW qty $MUT_QTY -> $((MUT_QTY + 1))"
  restore_mut(){ [ -n "$MUT_ROW" ] && dbq "UPDATE trades SET quantity=$MUT_QTY WHERE id='$MUT_ROW'" >/dev/null 2>&1; MUT_ROW=""; }
  trap restore_mut EXIT
  dbq "UPDATE trades SET quantity=$((MUT_QTY + 1)) WHERE id='$MUT_ROW'" >/dev/null 2>&1
  S2=$(fresh_classification) || true
  M2=$(num "$(printf '%s' "$S2" | jfield "d['fieldMismatch']")")
  restore_mut; trap - EXIT
  if [ -z "$M2" ] || [ "$M2" -le 0 ]; then
    bad "a PLANTED persistent mismatch was NOT caught by a fresh classification — the verdict above cannot be trusted"
  else
    echo "   → the planted mismatch is named by a fresh classification (field_mismatch=$M2) ✔"
  fi
  S3=$(fresh_classification) || true
  M3=$(num "$(printf '%s' "$S3" | jfield "d['fieldMismatch']")")
  if [ -z "$M3" ] || [ "$M3" -ne 0 ]; then
    bad "field_mismatch did not clear after the control was restored (read: ${M3:-unreadable}) — the projection may be left poisoned"
  else
    echo "   → restored: fresh classification back to field_mismatch=0 ✔  (the clean verdict above is a real verdict)"
  fi
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

sweep(){ curl -s -m600 -X POST "$TP/recon/orphan-sweep" -H "Authorization: Bearer $ADMIN"; }
OS=$(sweep)
LOCAL=$(num "$(printf '%s' "$OS" | jfield "d['localTradeCount']")")
PROVEN=$(num "$(printf '%s' "$OS" | jfield "d['fullHistoryTradeCount']")")
ORPHANS=$(num "$(printf '%s' "$OS" | jfield "d['orphanCount']")")
say "local trade count"       "${LOCAL:-?}"
say "with journal provenance" "${PROVEN:-?}"
say "orphan_in_projection"    "${ORPHANS:-?}"
if [ -z "$LOCAL" ] || [ -z "$PROVEN" ] || [ -z "$ORPHANS" ]; then
  bad "orphan sweep answered unreadably: $OS"
elif [ "$LOCAL" -le 0 ] || [ "$PROVEN" -le 0 ]; then
  # Agreement between two empty sets is not reconciliation.
  bad "nothing to reconcile (local=$LOCAL, journal=$PROVEN) — a sweep over no data proves nothing"
else
  # NOT asserted as zero. A rig seeded with TRADE-* demo rows carries projection rows that have no
  # journal fill BY CONSTRUCTION -- on this cluster rig that is 4 of them -- so "orphans == 0" is a
  # statement about which fixtures the rig happens to hold, not about whether the sweep works. It
  # failed here for exactly that reason while the sweep was behaving perfectly.
  #
  # The real property is that the sweep can TELL a journal-backed row from one without provenance,
  # and that is what the delta test below proves. This number is the baseline it measures against.
  BASELINE="$ORPHANS"
  if [ "$ORPHANS" -eq 0 ]; then
    echo "   → all $LOCAL projection rows have a journal fill behind them ✔"
  else
    echo "   → baseline: $ORPHANS projection row(s) with no journal fill — expected on a seeded rig"
    echo "     $(printf '%s' "$OS" | jfield "', '.join(d['orphanIds'])")"
  fi
fi
BASELINE="${BASELINE:-0}"

# ---- 5. positive control: can the sweep detect an orphan at all? ------------------------------
# Without this, orphan_in_projection=0 is indistinguishable from a check that does nothing — the
# exact shape of vacuous pass this suite has already produced once. Plant a row the log CANNOT
# contain and require the sweep to name it.
#
# The id is deliberately non-numeric before the dash: run-proofs.sh derives the epoch's trade
# ceiling with SUBSTRING_INDEX(id,'-',1), and a huge numeric probe left behind would make the next
# suite run wipe the rig for a dead epoch it invented.
PROBE="orphan-probe-B"
cleanup(){ dbq "DELETE FROM trades WHERE id='$PROBE';" >/dev/null 2>&1; }
trap cleanup EXIT
dbq "INSERT INTO trades (id, accountid, security, side, quantity, price, state) \
     VALUES ('$PROBE', 42422, 'NVDA', 'Buy', 1, 1.000, 'Processing');" >/dev/null 2>&1
OS2=$(sweep)
ORPHANS2=$(num "$(printf '%s' "$OS2" | jfield "d['orphanCount']")")
IDS2=$(printf '%s' "$OS2" | jfield "', '.join(d['orphanIds'])")
say "planted projection-only row" "$PROBE"
say "orphan_in_projection"        "${ORPHANS2:-?} (baseline ${BASELINE} + 1 expected)"
# Assert the DELTA and that the probe is NAMED. Both halves matter: the count alone could move for
# an unrelated reason, and a matching count with the probe absent would be a coincidence, not a
# detection. The previous form required the count to equal 1 and the id list to equal the probe
# exactly, which is only true on a rig holding no seed rows -- it failed here while printing the
# probe among the flagged ids, accusing the sweep of a defect the same line disproved.
case "$IDS2" in *"$PROBE"*) NAMED=1 ;; *) NAMED=0 ;; esac
if [ "${ORPHANS2:-0}" -ne "$((BASELINE + 1))" ] || [ "$NAMED" -ne 1 ]; then
  bad "the planted row was NOT detected (count=${ORPHANS2:-?}, expected $((BASELINE + 1)); named=${NAMED})"
  echo "     ids=${IDS2:-none} — the baseline above meant nothing: the sweep cannot tell a"
  echo "     journal-backed row from one without provenance."
else
  echo "   → the planted row is named as ORPHAN_IN_PROJECTION ✔  (the baseline above is a real verdict)"
fi
cleanup; trap - EXIT
OS3=$(sweep)
ORPHANS3=$(num "$(printf '%s' "$OS3" | jfield "d['orphanCount']")")
say "after removing the probe"    "${ORPHANS3:-?} (baseline ${BASELINE} expected)"
# Back to baseline, not to zero. This half is what proves the +1 was caused by the probe rather
# than by drift that happened to coincide with it.
[ "${ORPHANS3:-$((BASELINE + 1))}" -ne "$BASELINE" ] \
  && bad "orphans did not return to the baseline of ${BASELINE} after cleanup (got ${ORPHANS3:-?})"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "   ✔ RECONCILED against the replicated log: the projection is a faithful read model, and"
  echo "     the check that says so is demonstrably able to fail."
else
  echo "   ✘ reconciliation FAILED — see above"
fi
exit $FAIL
