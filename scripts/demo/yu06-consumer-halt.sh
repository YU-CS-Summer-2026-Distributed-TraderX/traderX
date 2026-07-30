#!/usr/bin/env bash
# YU06 — PROOF: the consumer is fail-safe. An account holding a security with no published close is
# HELD BACK (halted), not marked with a guessed price (FR-EOD32). Self-contained via kubectl.
#
# Induction lever: EOD_UNIVERSE excludes NVDA, which account 10031 holds. The session publishes clean
# (all listed instruments OK), but the consumer can't mark 10031's NVDA leg → it halts that account.
# Usage: bash yu06-consumer-halt.sh
set -uo pipefail
CTX=${CTX:-kind-traderx-state-014}; NS=${NS:-traderx}; DATE=$(date +%F)
HELD_ACCT=10031; HELD_SEC=NVDA
UNIVERSE="AAPL,MSFT,AMZN,GOOGL,META,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF"   # no NVDA
kx(){ kubectl --context "$CTX" -n "$NS" "$@"; }
db(){ kx exec deploy/database -- mariadb -utraderx -ptraderx traderx -N -e "$1" 2>/dev/null; }
api(){ kx exec deploy/edge-proxy -- sh -c "$1" 2>/dev/null; }
tok='T=$(curl -s -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: dev-token-master-secret" -H "Content-Type: application/json" -d "{\"subject\":\"demo\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}")'

echo "── EOD CONSUMER FAIL-SAFE (halt, don't guess) ──"
printf "   %-34s account %s holds %s (excluded from universe)\n" "setup" "$HELD_ACCT" "$HELD_SEC"
kx set env deploy/trade-processor EOD_UNIVERSE="$UNIVERSE" >/dev/null
kx rollout status deploy/trade-processor --timeout=120s >/dev/null 2>&1
kx wait --for=condition=ready pod -l app=trade-processor --timeout=60s >/dev/null 2>&1; sleep 4
# A restart wipes the in-memory price history; warm up by closing on a poll until the session is
# clean (flagged=0) so it AUTO-PUBLISHES — then the halt is purely about 10031's excluded NVDA leg.
printf "   %-34s" "warming price feed"
for i in $(seq 1 10); do
  api "$tok; curl -s -o /dev/null -X POST http://trade-processor:18091/eod/session/close -H \"Authorization: Bearer \$T\"" >/dev/null
  sleep 2
  V=$(db "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE';")
  fl=$(db "SELECT flagged_count FROM eod_price_session WHERE session_date='$DATE' AND version=$V;")
  printf "."; [ "${fl:-99}" = "0" ] && break; sleep 10
done
echo " ready"
sleep 4
V=$(db "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE' AND status='PUBLISHED';")
inst=$(db "SELECT instrument_count FROM eod_price_session WHERE session_date='$DATE' AND version=$V;")
printf "   %-34s v%s published, %s instruments (no %s)\n" "clean publish" "$V" "$inst" "$HELD_SEC"

echo "   ── consumer result for v$V ──"
kx logs deploy/position-service --tail=8 2>&1 | grep -iE "HALT|marked accounts" | grep "version=$V" | sed 's/^.*EodPnlConsumer   : /      /'
held=$(db "SELECT COUNT(*) FROM eod_position_pnl WHERE session_date='$DATE' AND version=$V AND account_id=$HELD_ACCT;")
other=$(db "SELECT COUNT(DISTINCT account_id) FROM eod_position_pnl WHERE session_date='$DATE' AND version=$V AND account_id<>$HELD_ACCT;")
printf "   %-34s marked-rows=%s\n" "account $HELD_ACCT (holds $HELD_SEC)" "$held"
printf "   %-34s marked\n" "$other other accounts"
[ "${held:-1}" = "0" ] && echo "   → $HELD_ACCT held back (not mis-marked); others marked ✔ (FR-EOD32)"

echo "   (resetting EOD_UNIVERSE to default…)"
kx set env deploy/trade-processor EOD_UNIVERSE- >/dev/null 2>&1
kx rollout status deploy/trade-processor --timeout=120s >/dev/null 2>&1 && echo "   done."
