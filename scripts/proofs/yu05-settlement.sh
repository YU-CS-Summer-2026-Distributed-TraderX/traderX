#!/usr/bin/env bash
# YU05 — PROOF: real settlement lifecycle. A booked trade starts Processing with a T+N settlement
# date (default T+1 business day) and advances to Settled by a scheduled sweep or a manual force
# (FR-PTC02/06). No more "settled the instant it books".
#
# Settlement state lives only in the trade-processor MariaDB projection (no HTTP read endpoint —
# FR-PTC07), so state is read from the DB via kubectl; the force is an HTTP POST.
#
# Prereq: source terminals in yu05-common.sh (trade-processor port-forward) + kube context reachable.
# Usage:
#   bash yu05-settlement.sh                 # book a crossing pair, settle the new trade
#   TRADE_ID=<id> bash yu05-settlement.sh   # act on a known Processing trade id
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
ADMIN=$(mint true '[]')

echo "── SETTLEMENT LIFECYCLE (Processing → Settled) ──"
order(){ curl -s -m8 -o /dev/null -w "%{http_code}" "$OM/orders" -H "Content-Type: application/json" -d "$1"; }
TID="${TRADE_ID:-}"

if [ -z "$TID" ]; then
  b=$(order '{"accountId":22214,"security":"IBM","side":"Buy","quantity":7,"limitPrice":200}')
  s=$(order '{"accountId":52355,"security":"IBM","side":"Sell","quantity":7,"limitPrice":190}')
  printf "   %-30s buy=%s sell=%s\n" "booked crossing IBM pair" "$b" "$s"
  sleep 2
  TID=$(dbq "SELECT id FROM trades WHERE state='Processing' ORDER BY created DESC LIMIT 1;")
  printf "   %-30s %s\n" "newest Processing trade" "${TID:-<none found>}"
fi
[ -z "$TID" ] && { echo "   no Processing trade — rerun with TRADE_ID=<id>"; exit 1; }

stateof(){ dbq "SELECT CONCAT_WS('  settlementDate=', state, IFNULL(settlementdate,'')) FROM trades WHERE id='$1';"; }
printf "   %-30s state=%s\n" "before" "$(stateof "$TID")"

code=$(curl -s -m8 -o /dev/null -w "%{http_code}" -X POST "$TP/trades/$TID/settlement/force" -H "Authorization: Bearer $ADMIN")
printf "   %-30s [HTTP %s]\n" "force settle (admin)" "$code"
sleep 1
after=$(stateof "$TID")
printf "   %-30s state=%s\n" "after" "$after"

case "$after" in Settled*) echo "   → Processing → Settled ✔";; *) echo "   → after='$after' (expected Settled)";; esac
