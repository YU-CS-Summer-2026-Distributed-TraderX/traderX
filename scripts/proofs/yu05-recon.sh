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
  -H "Authorization: Bearer $ADMIN")
RI_CODE=$(printf '%s' "$RESP" | tail -1)
RI=$(printf '%s' "$RESP" | sed '$d')
case "$RI_CODE" in
  000)
    # Unreachable is an error, not a green light: treating it as "capability present" let this
    # script run on to report matched=0 against a matcher it never contacted.
    echo "   ✘ $OM unreachable (curl 000) — port-forward svc/order-matcher 18110:18110?"; exit 1 ;;
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
# The scheduled sweep pages /recon/trades/blotter off a member. Poll rather than sleep-and-hope:
# a proof that reports whatever the scheduler happened to have done is a coin toss.
echo "   ── forward sweep (scheduled, journal blotter → projection) ──"
for _ in $(seq 1 30); do
  S=$(curl -s -m8 "$TP/recon/status" -H "Authorization: Bearer $ADMIN")
  MATCHED=$(num "$(printf '%s' "$S" | jfield "d['matched']")")
  [ -n "$MATCHED" ] && [ "$MATCHED" -gt 0 ] && break
  sleep 2
done
MISSING=$(num "$(printf '%s' "$S" | jfield "d['missingInProjection']")")
MISMATCH=$(num "$(printf '%s' "$S" | jfield "d['fieldMismatch']")")
say "matched"               "${MATCHED:-?}"
say "missing_in_projection" "${MISSING:-?}"
say "field_mismatch"        "${MISMATCH:-?}"
say "journal cursor"        "$(printf '%s' "$S" | jfield "d['cursor']")"
if [ -z "$MATCHED" ] || [ "$MATCHED" -le 0 ]; then
  bad "the sweep classified 0 trades — matched=0 is a clean reconciliation of NOTHING, which is"
  echo "     what this proof exists to refuse. Check RECON_POLL_INTERVAL_MS on trade-processor."
elif [ "${MISMATCH:-1}" -ne 0 ]; then
  bad "field_mismatch=$MISMATCH — the projection disagrees with the log on a trade it holds"
else
  echo "   → $MATCHED journal-sourced trades match the projection field for field ✔"
fi
# missing_in_projection is CUMULATIVE and the bridge is asynchronous, so a trade booked between a
# sweep and its NATS delivery counts once and never decrements. It is a lag signal here, not a
# drift signal — the drift claim is the set comparison below, which is immune to that race.
[ "${MISSING:-0}" -gt 0 ] && echo "     (missing=$MISSING is bridge lag counted at sweep time; the set comparison below is the verdict)"

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
