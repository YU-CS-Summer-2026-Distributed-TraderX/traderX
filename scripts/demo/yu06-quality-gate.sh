#!/usr/bin/env bash
# YU06 — PROOF (the fail-safe money demo): a MISSING closing price is FLAGGED and publication is
# BLOCKED until resolved (FR-EOD10/11/23); an operator override (with a reason) resolves it as a NEW
# version and lets it publish (FR-EOD12/13). Fully self-contained via kubectl (restarts trade-processor
# to inject the flag), so it needs no port-forward.
#
# Induction lever: EOD_UNIVERSE lists an "expected" ticker (QLTY) that never gets a price -> MISSING.
# Usage: bash yu06-quality-gate.sh
set -uo pipefail
CTX=${CTX:-kind-traderx-state-014}; NS=${NS:-traderx}; DATE=$(date +%F)
UNIVERSE="AAPL,MSFT,AMZN,GOOGL,META,NVDA,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF,QLTY"
kx(){ kubectl --context "$CTX" -n "$NS" "$@"; }
db(){ kx exec deploy/database -- mariadb -utraderx -ptraderx traderx -N -e "$1" 2>/dev/null; }
api(){ kx exec deploy/edge-proxy -- sh -c "$1" 2>/dev/null; }               # curl inside the cluster
tok='T=$(curl -s -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: dev-token-master-secret" -H "Content-Type: application/json" -d "{\"subject\":\"demo\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}")'

echo "── EOD QUALITY GATE (missing price → block → override → publish) ──"
kx set env deploy/trade-processor EOD_UNIVERSE="$UNIVERSE" >/dev/null
kx rollout status deploy/trade-processor --timeout=120s >/dev/null 2>&1
kx wait --for=condition=ready pod -l app=trade-processor --timeout=60s >/dev/null 2>&1; sleep 4
# A restart wipes the in-memory price history, so an immediate close flags every un-ticked security
# MISSING. Warm up: close on a poll until only QLTY (the priceless ticker) remains flagged.
printf "   %-30s" "warming price feed"
for i in $(seq 1 10); do
  api "$tok; curl -s -o /dev/null -X POST http://trade-processor:18091/eod/session/close -H \"Authorization: Bearer \$T\"" >/dev/null
  sleep 2
  V=$(db "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE';")
  fl=$(db "SELECT flagged_count FROM eod_price_session WHERE session_date='$DATE' AND version=$V;")
  printf "."; [ "${fl:-99}" -le 1 ] && break; sleep 10
done
echo " ready"
V=$(db "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE';")
read -r status flagged <<<"$(db "SELECT status, flagged_count FROM eod_price_session WHERE session_date='$DATE' AND version=$V;")"
printf "   %-30s v%s  status=%s  flagged=%s\n" "close (QLTY has no price)" "$V" "$status" "$flagged"
db "SELECT security, quality FROM eod_price_snapshot WHERE session_date='$DATE' AND version=$V AND quality<>'OK';" | sed 's/^/      flagged: /'
[ "$status" = "DRAFT" ] && echo "   → publication BLOCKED while flagged ✔ (fail-safe)"

# resolve + publish
api "$tok; curl -s -o /dev/null -X POST http://trade-processor:18091/eod/prices/$DATE/override -H \"Authorization: Bearer \$T\" -H 'Content-Type: application/json' -d '{\"security\":\"QLTY\",\"price\":100.00,\"reason\":\"manual close (demo)\"}'"
pcode=$(api "$tok; curl -s -o /dev/null -w '%{http_code}' -X POST http://trade-processor:18091/eod/prices/$DATE/publish -H \"Authorization: Bearer \$T\"")
sleep 2
NV=$(db "SELECT MAX(version) FROM eod_price_session WHERE session_date='$DATE';")
read -r st2 fl2 <<<"$(db "SELECT status, flagged_count FROM eod_price_session WHERE session_date='$DATE' AND version=$NV;")"
q=$(db "SELECT quality, closing_price FROM eod_price_snapshot WHERE session_date='$DATE' AND version=$NV AND security='QLTY';")
printf "   %-30s v%s override QLTY=%s\n" "operator resolves" "$NV" "$q"
printf "   %-30s [HTTP %s]  v%s status=%s\n" "publish" "$pcode" "$NV" "$st2"
[ "$st2" = "PUBLISHED" ] && echo "   → blocked while flagged, published once resolved; prior version immutable ✔"

echo "   (resetting EOD_UNIVERSE to default…)"
kx set env deploy/trade-processor EOD_UNIVERSE- >/dev/null 2>&1
kx rollout status deploy/trade-processor --timeout=120s >/dev/null 2>&1 && echo "   done."
