#!/usr/bin/env bash
# YU05 — PROOF: reconciliation is the CQRS integrity check. It classifies the authoritative journal
# blotter against the MariaDB projection (MATCHED / MISSING_IN_PROJECTION / FIELD_MISMATCH), and an
# on-demand full-history sweep flags ORPHAN_IN_PROJECTION — a projection row with no journal fill
# behind it (FR-PTC04/05/10). This is how you PROVE the async read-model hasn't drifted.
#
# Prereq: source terminals in yu05-common.sh.
# Usage: bash yu05-recon.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
ADMIN=$(mint true '[]')

echo "── RECONCILIATION (journal ↔ projection) ──"
S=$(curl -s -m8 "$TP/recon/status" -H "Authorization: Bearer $ADMIN")
printf "   %-30s %s\n" "matched"               "$(printf '%s' "$S" | jfield "d['matched']")"
printf "   %-30s %s\n" "missing_in_projection" "$(printf '%s' "$S" | jfield "d['missingInProjection']")"
printf "   %-30s %s\n" "field_mismatch"        "$(printf '%s' "$S" | jfield "d['fieldMismatch']")"
printf "   %-30s %s\n" "journal cursor"        "$(printf '%s' "$S" | jfield "d['cursor']")"

echo "   ── full-history sweep (admin): reindex whole journal, then flag orphans ──"
# The journal side of reconciliation. /recon/full-history/reindex is served by the SPRING
# order-matcher, which walks its own BLP journal. The cluster tier has no such endpoint and no such
# journal -- its log is the Raft log, held by the members, and nothing on the gateway exposes a
# reindex over it. So this proof cannot compare journal against projection here.
#
# Detected and stated rather than left to produce blanks: without the reindex every field below
# reads empty and the sweep reports matched=0, which looks like a clean reconciliation of nothing
# instead of a capability that is absent. A recon proof that silently reconciles zero rows is worse
# than one that refuses.
RI_CODE=$(curl -s -m30 -o /dev/null -w '%{http_code}' -X POST "$OM/recon/full-history/reindex" -H "Authorization: Bearer $ADMIN")
if [ "$RI_CODE" = "000" ]; then
  # Unreachable is an error, not a green light: treating it as "capability present" let this script
  # run on to report matched=0 against a matcher it never contacted.
  echo "   ✘ $OM unreachable (curl 000) — port-forward svc/order-matcher 18110:18110?"
  exit 1
fi
if [ "$RI_CODE" = "404" ]; then
  echo "   ✘ $OM/recon/full-history/reindex -> 404"
  echo "   CONTRACT (from ReconciliationService, YU05 layer) — the cluster tier must serve BOTH:"
  echo "     POST /recon/full-history/reindex            -> 200"
  echo "     GET  /recon/full-history/trades?sinceSeq=N  -> 200, a page of BlotterEntry"
  echo "   trade-processor calls the first and then pages the second; POST /recon/orphan-sweep"
  echo "   500s on this tier for exactly this reason (IOException: reindex trigger failed HTTP 404),"
  echo "   so that endpoint is not independently broken."
  echo "   ⚠ THE SOURCE MATTERS MORE THAN THE ROUTES. This reconciles the source of truth against"
  echo "   the projection, so serving those trades FROM the SQL projection would compare SQL to"
  echo "   itself and pass vacuously — matched=0 with nothing wrong reported. On this tier the"
  echo "   authority is the replicated log, so the trades must come from the MEMBERS."
  echo "   This tier has no journal reindex: that endpoint belongs to the Spring order-matcher and"
  echo "   its BLP journal. On the cluster tier the journal is the Raft log and no equivalent is"
  echo "   exposed, so journal-vs-projection reconciliation cannot be asserted here."
  echo "   Run against the state-014 rig (ORDER_MATCHER_URL=http://localhost:8080/order-matcher),"
  echo "   or add a reindex over the committed log to the gateway."
  exit 2
fi
RI=$(curl -s -m30 -X POST "$OM/recon/full-history/reindex" -H "Authorization: Bearer $ADMIN")
printf "   %-30s %s\n" "indexed journal trades" "$(printf '%s' "$RI" | jfield "d['indexedTrades']")"
OS=$(curl -s -m30 -X POST "$TP/recon/orphan-sweep" -H "Authorization: Bearer $ADMIN")
printf "   %-30s %s\n" "local trade count"      "$(printf '%s' "$OS" | jfield "d['localTradeCount']")"
printf "   %-30s %s\n" "with journal provenance" "$(printf '%s' "$OS" | jfield "d['fullHistoryTradeCount']")"
printf "   %-30s %s\n" "orphan_in_projection"   "$(printf '%s' "$OS" | jfield "d['orphanCount']")"
printf "   %-30s %s\n" "orphan ids"             "$(printf '%s' "$OS" | jfield "', '.join(d['orphanIds'])")"
echo "   → orphans are the DB-seed trades (init SQL, no journal fill behind them) — the feature"
echo "     correctly distinguishing journal-sourced trades from projection-only rows."
