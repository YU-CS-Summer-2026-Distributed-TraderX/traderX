#!/usr/bin/env bash
# YU06 — PROOF: the durable batch chain end to end. Publishing emits EOD_PRICES_READY (durable
# JetStream); position-service's consumer marks positions ONLY against the exact published snapshot
# version (FR-EOD21/30/31), writes eod_position_pnl rows, and emits eod.pnl.done.
#
# Prereq: source terminals in yu05-common.sh + kube context. position-service metrics via edge-proxy
# or its own port-forward (18090).
# Usage: bash yu06-chain-e2e.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
PS=${POSITION_SERVICE_URL:-http://localhost:18090}   # TO-VERIFY: reachable via port-forward or edge-proxy
ADMIN=$(mint true '[]')
DATE=$(date +%F)

echo "── EOD DURABLE CHAIN (publish → EOD_PRICES_READY → EOD P&L) ──"
# close + publish a clean session (override any flags first if needed — see yu06-quality-gate.sh)
curl -s -m15 -X POST "$TP/eod/session/close" -H "Authorization: Bearer $ADMIN" >/dev/null
sleep 1
curl -s -m10 -o /dev/null -X POST "$TP/eod/prices/$DATE/publish" -H "Authorization: Bearer $ADMIN"
V=$(dbq "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE' AND status='PUBLISHED';")
printf "   %-34s v%s status=PUBLISHED\n" "published snapshot" "${V:-<none — resolve flags first>}"

# poll for the consumer's output rows (position-service writes eod_position_pnl on the gate event)
printf "   %-34s " "consumer marks positions"
for i in $(seq 1 20); do
  n=$(dbq "SELECT COUNT(*) FROM eod_position_pnl WHERE session_date='$DATE' AND version=$V;")
  if [ "${n:-0}" -gt 0 ]; then echo "$n eod_position_pnl rows written for v$V (~$((i))×0.5s) ✔"; break; fi
  [ "$i" = 20 ] && echo "TIMEOUT — no P&L rows in 10s ✘"
  sleep 0.5
done
# show a couple of marked rows + the version pin
dbq "SELECT account_id, security, quantity, closing_price, market_value FROM eod_position_pnl WHERE session_date='$DATE' AND version=$V LIMIT 4;" | sed 's/^/      /'
echo "   ── consumer metrics (position-service) ──"
curl -s -m8 "$PS/actuator/prometheus" 2>/dev/null \
  | grep -E "traderx_eod_pnl_accounts_marked_total|traderx_eod_pnl_halted_total" | sed 's/^/      /' \
  || echo "      (position-service metrics unreachable — port-forward svc/position-service 18090)"
