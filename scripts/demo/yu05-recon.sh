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
RI=$(curl -s -m30 -X POST "$OM/recon/full-history/reindex" -H "Authorization: Bearer $ADMIN")
printf "   %-30s %s\n" "indexed journal trades" "$(printf '%s' "$RI" | jfield "d['indexedTrades']")"
OS=$(curl -s -m30 -X POST "$TP/recon/orphan-sweep" -H "Authorization: Bearer $ADMIN")
printf "   %-30s %s\n" "local trade count"      "$(printf '%s' "$OS" | jfield "d['localTradeCount']")"
printf "   %-30s %s\n" "with journal provenance" "$(printf '%s' "$OS" | jfield "d['fullHistoryTradeCount']")"
printf "   %-30s %s\n" "orphan_in_projection"   "$(printf '%s' "$OS" | jfield "d['orphanCount']")"
printf "   %-30s %s\n" "orphan ids"             "$(printf '%s' "$OS" | jfield "', '.join(d['orphanIds'])")"
echo "   → orphans are the DB-seed trades (init SQL, no journal fill behind them) — the feature"
echo "     correctly distinguishing journal-sourced trades from projection-only rows."
